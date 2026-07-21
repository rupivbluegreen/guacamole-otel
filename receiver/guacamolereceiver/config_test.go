// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0

package guacamolereceiver

import (
	"testing"

	"github.com/stretchr/testify/require"
)

func TestValidate(t *testing.T) {
	cfg := createDefaultConfig().(*Config)

	// default config has an endpoint but no username -> invalid
	require.Error(t, cfg.Validate())

	cfg.Username = "guacadmin"
	require.NoError(t, cfg.Validate())

	cfg.Endpoint = ""
	require.Error(t, cfg.Validate())
}
