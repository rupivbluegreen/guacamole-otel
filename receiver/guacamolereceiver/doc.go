// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0

// Package guacamolereceiver polls the Apache Guacamole REST API and reports
// active-connection metrics via OpenTelemetry. It is the ecosystem-native
// counterpart to the guacamole-ext listener extension: where the extension emits
// rich per-session telemetry from inside the webapp, this receiver provides a
// simple pull-based active-connection gauge for deployments that prefer scraping
// (and replaces the unmaintained tschoonj/guacamole_exporter).
package guacamolereceiver
