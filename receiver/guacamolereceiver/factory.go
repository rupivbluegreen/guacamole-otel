// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0

package guacamolereceiver

import (
	"context"
	"time"

	"go.opentelemetry.io/collector/component"
	"go.opentelemetry.io/collector/config/confighttp"
	"go.opentelemetry.io/collector/consumer"
	"go.opentelemetry.io/collector/receiver"
	"go.opentelemetry.io/collector/scraper"
	"go.opentelemetry.io/collector/scraper/scraperhelper"

	"github.com/guacamole-otel/guacamolereceiver/internal/metadata"
)

// NewFactory creates a factory for the Guacamole receiver.
func NewFactory() receiver.Factory {
	return receiver.NewFactory(
		metadata.Type,
		createDefaultConfig,
		receiver.WithMetrics(createMetricsReceiver, metadata.MetricsStability),
	)
}

func createDefaultConfig() component.Config {
	controller := scraperhelper.NewDefaultControllerConfig()
	controller.CollectionInterval = 30 * time.Second

	client := confighttp.NewDefaultClientConfig()
	client.Endpoint = "http://localhost:8080/guacamole"
	client.Timeout = 10 * time.Second

	return &Config{
		ControllerConfig: controller,
		ClientConfig:     client,
		DataSource:       "postgresql",
	}
}

func createMetricsReceiver(
	_ context.Context,
	settings receiver.Settings,
	cfg component.Config,
	next consumer.Metrics,
) (receiver.Metrics, error) {
	rCfg := cfg.(*Config)
	s := newScraper(settings, rCfg)

	scrp, err := scraper.NewMetrics(s.scrape, scraper.WithStart(s.start))
	if err != nil {
		return nil, err
	}

	return scraperhelper.NewMetricsController(
		&rCfg.ControllerConfig,
		settings,
		next,
		scraperhelper.AddScraper(metadata.Type, scrp),
	)
}
