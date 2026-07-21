// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0

package guacamolereceiver

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
	"go.opentelemetry.io/collector/component/componenttest"
	"go.opentelemetry.io/collector/receiver/receivertest"

	"github.com/guacamole-otel/guacamolereceiver/internal/metadata"
)

func TestScrapeActiveConnections(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/api/tokens":
			_, _ = w.Write([]byte(`{"authToken":"tok-123","dataSource":"postgresql"}`))
		case strings.HasSuffix(r.URL.Path, "/activeConnections"):
			require.Equal(t, "tok-123", r.Header.Get("Guacamole-Token"))
			// two active connections
			_, _ = w.Write([]byte(`{"id-a":{"identifier":"1"},"id-b":{"identifier":"2"}}`))
		default:
			http.Error(w, "not found", http.StatusNotFound)
		}
	}))
	defer srv.Close()

	cfg := createDefaultConfig().(*Config)
	cfg.Endpoint = srv.URL
	cfg.Username = "guacadmin"
	cfg.Password = "guacadmin"

	s := newScraper(receivertest.NewNopSettings(metadata.Type), cfg)
	require.NoError(t, s.start(context.Background(), componenttest.NewNopHost()))

	md, err := s.scrape(context.Background())
	require.NoError(t, err)

	require.Equal(t, 1, md.ResourceMetrics().Len())
	m := md.ResourceMetrics().At(0).ScopeMetrics().At(0).Metrics().At(0)
	require.Equal(t, "guacamole.active_connections", m.Name())
	require.Equal(t, int64(2), m.Gauge().DataPoints().At(0).IntValue())
}

func TestScrapeAuthFailure(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "Forbidden", http.StatusForbidden)
	}))
	defer srv.Close()

	cfg := createDefaultConfig().(*Config)
	cfg.Endpoint = srv.URL
	cfg.Username = "bad"
	cfg.Password = "bad"

	s := newScraper(receivertest.NewNopSettings(metadata.Type), cfg)
	require.NoError(t, s.start(context.Background(), componenttest.NewNopHost()))

	_, err := s.scrape(context.Background())
	require.Error(t, err)
}
