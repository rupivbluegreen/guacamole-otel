// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0

package guacamolereceiver

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"go.opentelemetry.io/collector/component"
	"go.opentelemetry.io/collector/pdata/pcommon"
	"go.opentelemetry.io/collector/pdata/pmetric"
	"go.opentelemetry.io/collector/receiver"

	"github.com/guacamole-otel/guacamolereceiver/internal/metadata"
)

type guacamoleScraper struct {
	cfg      *Config
	settings receiver.Settings
	client   *http.Client
}

func newScraper(settings receiver.Settings, cfg *Config) *guacamoleScraper {
	return &guacamoleScraper{cfg: cfg, settings: settings}
}

func (s *guacamoleScraper) start(ctx context.Context, host component.Host) error {
	client, err := s.cfg.ToClient(ctx, host.GetExtensions(), s.settings.TelemetrySettings)
	if err != nil {
		return err
	}
	s.client = client
	return nil
}

func (s *guacamoleScraper) scrape(ctx context.Context) (pmetric.Metrics, error) {
	token, dataSource, err := s.authenticate(ctx)
	if err != nil {
		return pmetric.NewMetrics(), err
	}

	active, err := s.activeConnectionCount(ctx, token, dataSource)
	if err != nil {
		return pmetric.NewMetrics(), err
	}

	return s.build(active), nil
}

type tokenResponse struct {
	AuthToken  string `json:"authToken"`
	DataSource string `json:"dataSource"`
}

func (s *guacamoleScraper) authenticate(ctx context.Context) (token, dataSource string, err error) {
	form := url.Values{}
	form.Set("username", s.cfg.Username)
	form.Set("password", string(s.cfg.Password))

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		s.endpoint("/api/tokens"), strings.NewReader(form.Encode()))
	if err != nil {
		return "", "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	body, err := s.do(req)
	if err != nil {
		return "", "", fmt.Errorf("authentication failed: %w", err)
	}

	var tr tokenResponse
	if err := json.Unmarshal(body, &tr); err != nil {
		return "", "", fmt.Errorf("could not parse token response: %w", err)
	}
	if tr.AuthToken == "" {
		return "", "", fmt.Errorf("no auth token returned")
	}

	ds := s.cfg.DataSource
	if ds == "" {
		ds = tr.DataSource
	}
	return tr.AuthToken, ds, nil
}

func (s *guacamoleScraper) activeConnectionCount(ctx context.Context, token, dataSource string) (int, error) {
	path := fmt.Sprintf("/api/session/data/%s/activeConnections", url.PathEscape(dataSource))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.endpoint(path), nil)
	if err != nil {
		return 0, err
	}
	req.Header.Set("Guacamole-Token", token)

	body, err := s.do(req)
	if err != nil {
		return 0, fmt.Errorf("could not list active connections: %w", err)
	}

	// The endpoint returns a JSON object keyed by active-connection id.
	var active map[string]json.RawMessage
	if err := json.Unmarshal(body, &active); err != nil {
		return 0, fmt.Errorf("could not parse active connections: %w", err)
	}
	return len(active), nil
}

func (s *guacamoleScraper) build(active int) pmetric.Metrics {
	md := pmetric.NewMetrics()
	sm := md.ResourceMetrics().AppendEmpty().ScopeMetrics().AppendEmpty()
	sm.Scope().SetName(metadata.ScopeName)

	m := sm.Metrics().AppendEmpty()
	m.SetName("guacamole.active_connections")
	m.SetDescription("Number of currently active Guacamole connections.")
	m.SetUnit("{connection}")

	dp := m.SetEmptyGauge().DataPoints().AppendEmpty()
	dp.SetTimestamp(pcommon.NewTimestampFromTime(time.Now()))
	dp.SetIntValue(int64(active))
	return md
}

func (s *guacamoleScraper) endpoint(path string) string {
	return strings.TrimRight(s.cfg.Endpoint, "/") + path
}

func (s *guacamoleScraper) do(req *http.Request) ([]byte, error) {
	resp, err := s.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer func() { _ = resp.Body.Close() }()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("unexpected status %d: %s", resp.StatusCode, strings.TrimSpace(string(body)))
	}
	return body, nil
}
