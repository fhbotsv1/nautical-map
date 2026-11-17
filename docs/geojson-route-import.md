# GeoJSON route import format

The app imports a GeoJSON **FeatureCollection** containing one `LineString` feature per route variant.

This is an interchange format, not a source of route data. Only import geometries that you have obtained from a documented AIS analysis or another source you are licensed to use. The app does not create or validate commercial routes.

## Required structure

Each feature must have:
- `geometry.type` = "LineString"
- `geometry.coordinates` with at least two WGS84 positions in **[longitude, latitude]** order
- `properties.source_name`
- Origin and destination port names, countries and coordinates:
  - `origin_name`, `origin_country`, `origin_latitude`, `origin_longitude`
  - `destination_name`, `destination_country`, `destination_latitude`, `destination_longitude`

Optional properties:
`origin_unlocode`, `destination_unlocode`, `variant_name`, `distance_nm`, `vessel_categories`, `ais_sample_count`, `observation_start`, `observation_end`, `source_license`, `quality_status`, `notes`, `point_type`.

## Example structure

The following is a schema illustration only. The coordinates and names are placeholders, not a real shipping route.

```json
{
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "properties": {
        "origin_name": "ORIGIN PORT",
        "origin_country": "COUNTRY A",
        "origin_unlocode": "AAAAA",
        "origin_latitude": 12.345,
        "origin_longitude": 45.678,
        "destination_name": "DESTINATION PORT",
        "destination_country": "COUNTRY B",
        "destination_unlocode": "BBBBB",
        "destination_latitude": 23.456,
        "destination_longitude": 56.789,
        "variant_name": "AIS-derived corridor 1",
        "distance_nm": 1234,
        "vessel_categories": "Container;Bulk carrier",
        "ais_sample_count": 250,
        "observation_start": "2024-01-01",
        "observation_end": "2024-12-31",
        "source_name": "Name of AIS dataset or analysis",
        "source_license": "License or terms URL/name",
        "quality_status": "UNVERIFIED",
        "notes": "Describe filtering and processing method",
        "point_type": "AIS_DERIVED"
      },
      "geometry": {
        "type": "LineString",
        "coordinates": [
          [45.678, 12.345],
          [48.000, 14.000],
          [52.000, 18.000],
          [56.789, 23.456]
        ]
      }
    }
  ]
}
```

## Import behavior

- Ports are matched by UN/LOCODE when supplied; otherwise by case-insensitive name and country.
- Route variants are identified by origin, destination and variant name.
- Re-importing an existing variant replaces its stored point sequence.
- The entire file is imported in one database transaction. If a feature is invalid, the import is rolled back.
- Coordinates are range-checked, but the app does not assess navigational safety, AIS quality or route authenticity.
