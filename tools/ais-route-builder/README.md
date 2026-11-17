# AIS port-to-port route builder

A standalone Python tool that converts historical AIS positions plus port-call
records into GeoJSON accepted by the Android app's **Import GeoJSON routes**
feature. It is separate from the Android build and uses only the Python standard
library.

## Important data rule

This tool does not create synthetic shipping routes. A route is emitted only
when actual AIS positions can be associated with consecutive port calls for the
same MMSI. The representative geometry is selected from an observed voyage
(the medoid track); it is not a smoothed or invented centreline. The supplied
port-call coordinates are used as the two endpoints.

The tool cannot establish that a source dataset is complete, correctly licensed,
or globally representative. Verify provider terms and retain the source's
attribution. Keep the output status as \`UNVERIFIED\` until the route has been
independently reviewed. AIS-derived corridors are not navigational advice.

## Requirements

- Python 3.10 or newer
- No third-party packages

## Input 1: AIS positions CSV

Required columns:

| Column | Meaning |
|---|---|
| \`mmsi\` | Vessel MMSI, consistent with port-call file |
| \`timestamp\` | ISO-8601 UTC timestamp, e.g. \`2025-01-10T14:30:00Z\` |
| \`latitude\`, \`longitude\` | WGS84 decimal degrees |
| \`sog_knots\` | Speed over ground in knots |

Optional column: \`vessel_category\` (e.g. Container, Bulk carrier, Tanker).
Missing categories are labelled Unknown. Rows above the configurable SOG limit
are ignored. Invalid coordinates or malformed required values stop processing
with a row-specific error.

## Input 2: port calls CSV

Required columns:

| Column | Meaning |
|---|---|
| \`mmsi\` | Same vessel identifier as AIS positions |
| \`port_name\`, \`country\` | Port identity |
| \`latitude\`, \`longitude\` | WGS84 port-call coordinate |
| \`arrival\` | Arrival time in ISO-8601 |
| \`departure\` | Departure time in ISO-8601 |

Optional column: \`unlocode\`. When present, it is used to match the port
identity; otherwise the normalized port name and country are used.

Each consecutive pair of port calls for a vessel defines a candidate voyage.
AIS positions between the first call's departure and the next call's arrival
form its track. A candidate is kept only when it has at least two AIS positions.
The voyage duration limit is configurable.

## Run

From the repository root:

\`\`\`bash
python tools/ais-route-builder/ais_route_builder.py ^
  --positions path/to/ais_positions.csv ^
  --port-calls path/to/port_calls.csv ^
  --output build/routes.geojson ^
  --source-name "Provider dataset name, release/date" ^
  --source-license "Provider licence or terms URL" ^
  --min-voyages 2 ^
  --tolerance-nm 20
\`\`\`

On macOS/Linux, replace the Windows line-continuation caret \`^\` with
backslashes or put the command on one line.

Options:

- \`--min-voyages 2\`: require at least this many voyages in a corridor cluster.
  Increase it for more conservative outputs.
- \`--tolerance-nm 20\`: symmetric mean nearest-point distance used to group
  similar tracks. This is a practical first-pass clustering rule, not a formal
  maritime route classification.
- \`--max-sog 60\`: discard positions with higher SOG.
- \`--max-voyage-hours 1000\`: skip implausibly long port-call intervals.
- \`--quality-status UNVERIFIED\`: provenance status written to GeoJSON.

The route clustering is greedy and order-dependent. It uses a representative
observed track for each cluster and preserves separate clusters as route
variants. Review outputs on a map before importing them.

## Output

The result is a GeoJSON FeatureCollection with one LineString per route
variant. Properties include the exact required Android importer fields:
origin/destination names, countries and coordinates, \`source_name\`, plus
variant name, distance in nautical miles, vessel categories, AIS sample count,
observation dates, licence, quality status, processing notes and
\`point_type=AIS_DERIVED\`.

Coordinates are written in GeoJSON order: \`[longitude, latitude]\`.

## Tests

Run from the repository root:

\`\`\`bash
python -m unittest discover -s tools/ais-route-builder/tests -v
\`\`\`

Tests use clearly synthetic fixtures only to test parsing and clustering logic.
They are not included as route data and must never be imported as real traffic.
