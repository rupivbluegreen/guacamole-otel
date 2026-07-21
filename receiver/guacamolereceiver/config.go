// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0

package guacamolereceiver

import (
	"errors"

	"go.opentelemetry.io/collector/config/confighttp"
	"go.opentelemetry.io/collector/config/configopaque"
	"go.opentelemetry.io/collector/scraper/scraperhelper"
)

// Config for the Guacamole scraper receiver. It polls the Guacamole REST API for
// active-connection counts — an ecosystem-native replacement for the unmaintained
// tschoonj/guacamole_exporter.
type Config struct {
	scraperhelper.ControllerConfig `mapstructure:",squash"`
	confighttp.ClientConfig        `mapstructure:",squash"`

	// Username / Password authenticate against the Guacamole REST API.
	Username string              `mapstructure:"username"`
	Password configopaque.String `mapstructure:"password"`

	// DataSource is the Guacamole auth data source (e.g. "postgresql", "mysql").
	// If empty, the data source returned by the token request is used.
	DataSource string `mapstructure:"data_source"`
}

// Validate checks that required fields are set.
func (c *Config) Validate() error {
	if c.Endpoint == "" {
		return errors.New("endpoint must be specified")
	}
	if c.Username == "" {
		return errors.New("username must be specified")
	}
	return nil
}
