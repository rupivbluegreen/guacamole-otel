// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0

package guacamolereceiver

import (
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/guacamole-otel/guacamolereceiver/internal/metadata"
)

func TestNewFactory(t *testing.T) {
	f := NewFactory()
	require.Equal(t, metadata.Type, f.Type())

	cfg := f.CreateDefaultConfig()
	require.NotNil(t, cfg)
	_, ok := cfg.(*Config)
	require.True(t, ok)
}
