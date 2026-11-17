#!/usr/bin/env python3
"""Convert the Brest research prototype dataset to the Android app's GeoJSON format.

This is a separate converter from ais_route_builder.py: prototypes.csv contains
research-derived representative geometries, not complete observed AIS voyages.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
from collections import defaultdict
from pathlib import Path

SOURCE_NAME = (
    "Maritime routes and vessel tracklet dataset for vessel-to-route "
    "association (Brest)"
)
SOURCE_LICENSE = "CC BY-NC-SA 4.0"
METRES_PER_NAUTICAL_MILE = 1852.0


def read_table(path: Path, required: set[str]) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8-sig") as stream:
        reader = csv.DictReader(stream, delimiter="|")
        missing = required - set(reader.fieldnames or [])
        if missing:
            raise ValueError(
                f"{path} is missing required columns: {', '.join(sorted(missing))}"
            )
        return list(reader)


def finite_number(row: dict[str, str], field: str, path: Path, line: int) -> float:
    try:
        value = float(row[field])
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{path}:{line}: invalid {field}: {row.get(field)!r}") from exc
    if not math.isfinite(value):
        raise ValueError(f"{path}:{line}: {field} must be finite")
    return value


def validate_coordinate(lon: float, lat: float, path: Path, line: int) -> None:
    if not -180 <= lon <= 180 or not -90 <= lat <= 90:
        raise ValueError(f"{path}:{line}: invalid WGS84 coordinate [{lon}, {lat}]")


def haversine_nm(a: tuple[float, float], b: tuple[float, float]) -> float:
    """Coordinates are (longitude, latitude)."""
    radius_nm = 3440.065
    lon1, lat1, lon2, lat2 = map(
        math.radians, (a[0], a[1], b[0], b[1])
    )
    dlon, dlat = lon2 - lon1, lat2 - lat1
    h = (
        math.sin(dlat / 2) ** 2
        + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    )
    return 2 * radius_nm * math.asin(min(1.0, math.sqrt(h)))


def convert_dataset(
    dataset_dir: Path,
    *,
    max_endpoint_gap_nm: float | None = None,
) -> tuple[dict, list[str]]:
    """Return importer-compatible GeoJSON and human-readable conversion warnings."""
    nomen_path = dataset_dir / "nomen.csv"
    poi_path = dataset_dir / "poi.csv"
    prototypes_path = dataset_dir / "prototypes.csv"

    nomen_rows = read_table(
        nomen_path, {"route", "originport", "destinationport", "length"}
    )
    poi_rows = read_table(
        poi_path, {"portname", "offname", "longitude", "latitude", "locode"}
    )
    prototype_rows = read_table(
        prototypes_path, {"route", "pointnumber", "longitude", "latitude"}
    )

    ports: dict[str, dict] = {}
    for line, row in enumerate(poi_rows, start=2):
        name = row["portname"].strip()
        if not name:
            raise ValueError(f"{poi_path}:{line}: empty portname")
        lon = finite_number(row, "longitude", poi_path, line)
        lat = finite_number(row, "latitude", poi_path, line)
        validate_coordinate(lon, lat, poi_path, line)
        if name in ports:
            raise ValueError(f"{poi_path}:{line}: duplicate portname {name!r}")
        ports[name] = {
            "name": name,
            "display_name": row["offname"].strip() or name,
            "longitude": lon,
            "latitude": lat,
            "unlocode": row["locode"].strip().upper(),
        }

    routes: dict[str, dict] = {}
    for line, row in enumerate(nomen_rows, start=2):
        route_id = row["route"].strip()
        if not route_id or route_id in routes:
            raise ValueError(f"{nomen_path}:{line}: empty or duplicate route ID")
        length_m = finite_number(row, "length", nomen_path, line)
        if length_m <= 0:
            raise ValueError(f"{nomen_path}:{line}: route length must be positive")
        routes[route_id] = {
            "origin": row["originport"].strip(),
            "destination": row["destinationport"].strip(),
            "distance_nm": length_m / METRES_PER_NAUTICAL_MILE,
        }

    grouped: dict[str, list[tuple[int, float, float]]] = defaultdict(list)
    for line, row in enumerate(prototype_rows, start=2):
        route_id = row["route"].strip()
        if route_id not in routes:
            raise ValueError(
                f"{prototypes_path}:{line}: unknown route ID {route_id!r}"
            )
        try:
            point_number = int(row["pointnumber"])
        except (TypeError, ValueError) as exc:
            raise ValueError(
                f"{prototypes_path}:{line}: invalid pointnumber"
            ) from exc
        lon = finite_number(row, "longitude", prototypes_path, line)
        lat = finite_number(row, "latitude", prototypes_path, line)
        validate_coordinate(lon, lat, prototypes_path, line)
        grouped[route_id].append((point_number, lon, lat))

    features: list[dict] = []
    warnings: list[str] = []
    for route_id, route in routes.items():
        origin = ports.get(route["origin"])
        destination = ports.get(route["destination"])
        if origin is None or destination is None:
            missing = [
                name for name, value in (
                    (route["origin"], origin), (route["destination"], destination)
                ) if value is None
            ]
            warnings.append(
                f"{route_id} skipped: endpoint(s) absent from poi.csv: "
                + ", ".join(missing)
            )
            continue
        ordered = sorted(grouped.get(route_id, []), key=lambda item: item[0])
        if len(ordered) < 2:
            warnings.append(
                f"{route_id} skipped: prototype has fewer than two points"
            )
            continue
        point_numbers = [item[0] for item in ordered]
        if len(point_numbers) != len(set(point_numbers)):
            raise ValueError(f"{prototypes_path}: duplicate pointnumber in {route_id}")

        coordinates = [[round(lon, 6), round(lat, 6)] for _, lon, lat in ordered]
        origin_gap = haversine_nm(
            (coordinates[0][0], coordinates[0][1]),
            (origin["longitude"], origin["latitude"]),
        )
        destination_gap = haversine_nm(
            (coordinates[-1][0], coordinates[-1][1]),
            (destination["longitude"], destination["latitude"]),
        )
        if max_endpoint_gap_nm is not None and (
            origin_gap > max_endpoint_gap_nm
            or destination_gap > max_endpoint_gap_nm
        ):
            warnings.append(
                f"{route_id} skipped: prototype endpoint gap exceeds "
                f"{max_endpoint_gap_nm:g} NM "
                f"(origin {origin_gap:.2f}, destination {destination_gap:.2f})"
            )
            continue

        properties = {
            "origin_name": origin["display_name"],
            "origin_country": "France",
            "origin_latitude": origin["latitude"],
            "origin_longitude": origin["longitude"],
            "destination_name": destination["display_name"],
            "destination_country": "France",
            "destination_latitude": destination["latitude"],
            "destination_longitude": destination["longitude"],
            "variant_name": f"Brest research prototype {route_id}",
            "distance_nm": round(route["distance_nm"], 2),
            "source_name": SOURCE_NAME,
            "source_license": SOURCE_LICENSE,
            "quality_status": "UNVERIFIED",
            "notes": (
                f"Research-derived prototype geometry for source route {route_id}. "
                "Coordinates are ordered prototype points, not a single observed "
                "voyage. Port metadata comes from poi.csv. Prototype endpoints "
                f"are {origin_gap:.2f} NM from the origin POI and "
                f"{destination_gap:.2f} NM from the destination POI. "
                "For research/display testing only; not navigational advice."
            ),
            "point_type": "RESEARCH_PROTOTYPE",
            "source_route_id": route_id,
            "prototype_origin_gap_nm": round(origin_gap, 2),
            "prototype_destination_gap_nm": round(destination_gap, 2),
        }
        if origin["unlocode"]:
            properties["origin_unlocode"] = origin["unlocode"]
        if destination["unlocode"]:
            properties["destination_unlocode"] = destination["unlocode"]

        features.append({
            "type": "Feature",
            "properties": properties,
            "geometry": {"type": "LineString", "coordinates": coordinates},
        })

    return {"type": "FeatureCollection", "features": features}, warnings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", required=True, type=Path,
                        help="Folder containing nomen.csv, poi.csv and prototypes.csv")
    parser.add_argument("--output", required=True, type=Path,
                        help="Output GeoJSON file")
    parser.add_argument(
        "--max-endpoint-gap-nm", type=float, default=None,
        help="Optional maximum allowed prototype-to-port endpoint gap in NM",
    )
    args = parser.parse_args()
    if args.max_endpoint_gap_nm is not None and args.max_endpoint_gap_nm < 0:
        parser.error("--max-endpoint-gap-nm cannot be negative")

    result, warnings = convert_dataset(
        args.input_dir,
        max_endpoint_gap_nm=args.max_endpoint_gap_nm,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(f"Wrote {len(result['features'])} route features to {args.output}")
    for warning in warnings:
        print(f"WARNING: {warning}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
