# Little Bird Nautical Map

An Android offline-first marine map foundation, built with Kotlin and Jetpack Compose.

## Current foundation

- Offline-only map rendering from user-imported MBTiles, SQLite, ZIP or GEMF tile archives.
- No online tile source is configured and the app does not request internet permission.
- GPS position, latitude/longitude, speed over ground (knots) and course over ground.
- Local SQLite catalogue for ports, commercial port-to-port routes and ordered route geometry.
- Route metadata includes AIS sample count, observation period, vessel categories, data source, licence and verification status.
- Separate Map, Routes, Ports and Offline Data screens.
- A standalone Python AIS route-builder is available under `tools/ais-route-builder/`.

## Map data

Import a compatible raster tile archive from the **Offline data** screen. Map packs are copied to app-private storage. The first version does not bundle map tiles or download them.

The current renderer uses osmdroid's offline archive support. osmdroid 6.1.20 is archived upstream; the map integration is isolated so it can be replaced if required.

## Commercial route data

The database is intentionally empty. No fabricated shipping routes are seeded.

The Python pipeline in `tools/ais-route-builder/` accepts historical AIS position CSV data and port-call CSV data. It links consecutive port calls for the same vessel, extracts the observed AIS positions for each voyage, groups similar tracks into corridor variants, selects an observed representative track, and exports GeoJSON compatible with the Android importer.

The pipeline requires at least two AIS positions per candidate voyage and defaults to at least two voyages per output corridor. These thresholds are configurable. Its clustering method is a first-pass similarity heuristic and outputs remain `UNVERIFIED` by default. Verify source coverage, provider terms, port-call quality and route geometry before importing. No real route dataset is bundled.

See:
- [AIS route builder guide](tools/ais-route-builder/README.md)
- [GeoJSON route import format](docs/geojson-route-import.md)

Route geometry is reference traffic information, not a safe-passage recommendation.

## Build

- Android Studio
- JDK 17
- compile SDK 36
- minimum Android 8.0 (API 26)

Open the project, sync Gradle and run on an Android device. Grant location permission to display GPS data.

## Navigation safety

This app is not an ECDIS and does not currently include official ENC/S-57 charts, depth soundings, aids to navigation, notices to mariners, tide/current data, weather routing or validated passage plans. Do not use it as a replacement for approved navigational equipment.
