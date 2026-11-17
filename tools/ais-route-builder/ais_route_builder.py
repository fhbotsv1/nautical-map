#!/usr/bin/env python3
"""Build AIS-derived port-to-port route corridors as GeoJSON.

Input CSVs are documented in README.md. This tool deliberately does not
invent tracks: every output geometry is selected from observed AIS positions.
Only the two port-call coordinates are used as endpoint anchors.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable


@dataclass(frozen=True)
class PortCall:
    mmsi: str
    port_name: str
    country: str
    unlocode: str
    latitude: float
    longitude: float
    arrival: datetime
    departure: datetime


@dataclass
class Position:
    timestamp: datetime
    latitude: float
    longitude: float
    sog_knots: float
    vessel_category: str


@dataclass
class Voyage:
    origin: PortCall
    destination: PortCall
    positions: list[Position]

    @property
    def key(self) -> tuple[str, str]:
        return (port_key(self.origin), port_key(self.destination))


def parse_time(value: str) -> datetime:
    value = value.strip().replace("Z", "+00:00")
    result = datetime.fromisoformat(value)
    if result.tzinfo is None:
        result = result.replace(tzinfo=timezone.utc)
    return result.astimezone(timezone.utc)


def number(row: dict, field: str) -> float:
    value = float(row[field])
    if not math.isfinite(value):
        raise ValueError(f"{field} must be finite")
    return value


def validate_coordinate(lat: float, lon: float) -> None:
    if not -90 <= lat <= 90 or not -180 <= lon <= 180:
        raise ValueError(f"Invalid WGS84 coordinate: {lat}, {lon}")


def port_key(port: PortCall) -> str:
    return port.unlocode.upper() if port.unlocode else (
        port.port_name.strip().casefold() + "|" + port.country.strip().casefold()
    )


def read_positions(path: Path, max_sog: float) -> dict[str, list[Position]]:
    grouped: dict[str, list[Position]] = defaultdict(list)
    with path.open(newline="", encoding="utf-8-sig") as stream:
        reader = csv.DictReader(stream)
        required = {"mmsi", "timestamp", "latitude", "longitude", "sog_knots"}
        missing = required - set(reader.fieldnames or [])
        if missing:
            raise ValueError(f"AIS positions CSV missing columns: {', '.join(sorted(missing))}")
        for line, row in enumerate(reader, start=2):
            try:
                lat, lon = number(row, "latitude"), number(row, "longitude")
                validate_coordinate(lat, lon)
                sog = number(row, "sog_knots")
                if sog < 0 or sog > max_sog:
                    continue
                mmsi = row["mmsi"].strip()
                if not mmsi:
                    continue
                grouped[mmsi].append(Position(
                    parse_time(row["timestamp"]), lat, lon, sog,
                    (row.get("vessel_category") or "Unknown").strip() or "Unknown"
                ))
            except (ValueError, KeyError) as exc:
                raise ValueError(f"{path}:{line}: {exc}") from exc
    for values in grouped.values():
        values.sort(key=lambda p: p.timestamp)
    return grouped


def read_port_calls(path: Path) -> dict[str, list[PortCall]]:
    grouped: dict[str, list[PortCall]] = defaultdict(list)
    with path.open(newline="", encoding="utf-8-sig") as stream:
        reader = csv.DictReader(stream)
        required = {"mmsi", "port_name", "country", "latitude", "longitude", "arrival", "departure"}
        missing = required - set(reader.fieldnames or [])
        if missing:
            raise ValueError(f"Port calls CSV missing columns: {', '.join(sorted(missing))}")
        for line, row in enumerate(reader, start=2):
            try:
                lat, lon = number(row, "latitude"), number(row, "longitude")
                validate_coordinate(lat, lon)
                arrival, departure = parse_time(row["arrival"]), parse_time(row["departure"])
                if departure < arrival:
                    raise ValueError("departure is earlier than arrival")
                mmsi = row["mmsi"].strip()
                if not mmsi:
                    continue
                grouped[mmsi].append(PortCall(
                    mmsi, row["port_name"].strip(), row["country"].strip(),
                    (row.get("unlocode") or "").strip().upper(), lat, lon,
                    arrival, departure
                ))
            except (ValueError, KeyError) as exc:
                raise ValueError(f"{path}:{line}: {exc}") from exc
    for calls in grouped.values():
        calls.sort(key=lambda p: p.arrival)
    return grouped


def extract_voyages(
    calls_by_mmsi: dict[str, list[PortCall]],
    positions_by_mmsi: dict[str, list[Position]],
    max_gap_hours: float,
) -> list[Voyage]:
    voyages: list[Voyage] = []
    for mmsi, calls in calls_by_mmsi.items():
        positions = positions_by_mmsi.get(mmsi, [])
        for origin, destination in zip(calls, calls[1:]):
            if port_key(origin) == port_key(destination):
                continue
            if destination.arrival <= origin.departure:
                continue
            if (destination.arrival - origin.departure).total_seconds() > max_gap_hours * 3600:
                continue
            track = [
                p for p in positions
                if origin.departure <= p.timestamp <= destination.arrival
            ]
            # Include port-call coordinates as endpoints; all intermediate
            # geometry remains observed AIS positions.
            points = [(origin.latitude, origin.longitude)]
            points.extend((p.latitude, p.longitude) for p in track)
            points.append((destination.latitude, destination.longitude))
            compact: list[tuple[float, float]] = []
            for point in points:
                if not compact or haversine_nm(compact[-1], point) >= 0.05:
                    compact.append(point)
            if len(compact) >= 3 and len(track) >= 2:
                voyages.append(Voyage(origin, destination, track))
    return voyages


def haversine_nm(a: tuple[float, float], b: tuple[float, float]) -> float:
    radius_nm = 3440.065
    lat1, lon1, lat2, lon2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    dlat, dlon = lat2 - lat1, lon2 - lon1
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 2 * radius_nm * math.asin(min(1.0, math.sqrt(h)))


def voyage_coordinates(voyage: Voyage) -> list[tuple[float, float]]:
    points = [(voyage.origin.latitude, voyage.origin.longitude)]
    points.extend((p.latitude, p.longitude) for p in voyage.positions)
    points.append((voyage.destination.latitude, voyage.destination.longitude))
    compact = [points[0]]
    for point in points[1:]:
        if haversine_nm(compact[-1], point) >= 0.05:
            compact.append(point)
    return compact


def sample_track(track: list[tuple[float, float]], count: int = 24) -> list[tuple[float, float]]:
    if len(track) <= count:
        return track
    return [track[round(i * (len(track) - 1) / (count - 1))] for i in range(count)]


def directed_mean_distance(
    source: list[tuple[float, float]], target: list[tuple[float, float]]
) -> float:
    return sum(min(haversine_nm(p, q) for q in target) for p in source) / len(source)


def track_distance(
    a: list[tuple[float, float]], b: list[tuple[float, float]]
) -> float:
    # Symmetric mean nearest-point distance; a conservative corridor similarity
    # test, not a navigational or hydrodynamic route model.
    sa, sb = sample_track(a), sample_track(b)
    return (directed_mean_distance(sa, sb) + directed_mean_distance(sb, sa)) / 2


def cluster_voyages(voyages: list[Voyage], tolerance_nm: float) -> list[list[Voyage]]:
    clusters: list[list[Voyage]] = []
    representatives: list[list[tuple[float, float]]] = []
    for voyage in sorted(voyages, key=lambda v: (v.origin.mmsi, v.origin.departure)):
        track = voyage_coordinates(voyage)
        matches = [
            (track_distance(track, representative), index)
            for index, representative in enumerate(representatives)
        ]
        if matches:
            distance, index = min(matches)
            if distance <= tolerance_nm:
                clusters[index].append(voyage)
                continue
        clusters.append([voyage])
        representatives.append(track)
    return clusters


def line_distance_nm(track: list[tuple[float, float]]) -> float:
    return sum(haversine_nm(a, b) for a, b in zip(track, track[1:]))


def build_feature(
    cluster: list[Voyage], variant_number: int, source_name: str,
    source_license: str, quality_status: str, tolerance_nm: float
) -> dict:
    # Pick the observed voyage closest to all other tracks as a medoid.
    tracks = [voyage_coordinates(v) for v in cluster]
    scores = [
        sum(track_distance(candidate, other) for other in tracks)
        for candidate in tracks
    ]
    representative = tracks[scores.index(min(scores))]
    first = cluster[0]
    categories = sorted({
        p.vessel_category for voyage in cluster for p in voyage.positions
    })
    timestamps = [
        p.timestamp for voyage in cluster for p in voyage.positions
    ]
    return {
        "type": "Feature",
        "properties": {
            "origin_name": first.origin.port_name,
            "origin_country": first.origin.country,
            "origin_unlocode": first.origin.unlocode,
            "origin_latitude": first.origin.latitude,
            "origin_longitude": first.origin.longitude,
            "destination_name": first.destination.port_name,
            "destination_country": first.destination.country,
            "destination_unlocode": first.destination.unlocode,
            "destination_latitude": first.destination.latitude,
            "destination_longitude": first.destination.longitude,
            "variant_name": f"AIS corridor {variant_number}",
            "distance_nm": round(line_distance_nm(representative), 2),
            "vessel_categories": ";".join(categories),
            "ais_sample_count": sum(len(v.positions) for v in cluster),
            "observation_start": min(timestamps).date().isoformat(),
            "observation_end": max(timestamps).date().isoformat(),
            "source_name": source_name,
            "source_license": source_license,
            "quality_status": quality_status,
            "notes": (
                f"{len(cluster)} observed voyages; representative is the medoid "
                f"observed track; clustering tolerance {tolerance_nm:g} NM. "
                "Endpoints use supplied port-call coordinates."
            ),
            "point_type": "AIS_DERIVED"
        },
        "geometry": {
            "type": "LineString",
            "coordinates": [[round(lon, 6), round(lat, 6)] for lat, lon in representative]
        }
    }


def build_geojson(
    voyages: Iterable[Voyage], source_name: str, source_license: str,
    quality_status: str, tolerance_nm: float, min_voyages: int
) -> dict:
    by_pair: dict[tuple[str, str], list[Voyage]] = defaultdict(list)
    for voyage in voyages:
        by_pair[voyage.key].append(voyage)
    features = []
    for pair_voyages in by_pair.values():
        clusters = cluster_voyages(pair_voyages, tolerance_nm)
        variant = 0
        for cluster in clusters:
            if len(cluster) < min_voyages:
                continue
            variant += 1
            features.append(build_feature(
                cluster, variant, source_name, source_license,
                quality_status, tolerance_nm
            ))
    return {"type": "FeatureCollection", "features": features}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--positions", required=True, type=Path, help="AIS positions CSV")
    parser.add_argument("--port-calls", required=True, type=Path, help="Port calls CSV")
    parser.add_argument("--output", required=True, type=Path, help="Output GeoJSON path")
    parser.add_argument("--source-name", required=True, help="Dataset/provider and dataset version")
    parser.add_argument("--source-license", required=True, help="Licence or terms reference")
    parser.add_argument("--quality-status", default="UNVERIFIED")
    parser.add_argument("--tolerance-nm", type=float, default=20.0)
    parser.add_argument("--min-voyages", type=int, default=2)
    parser.add_argument("--max-sog", type=float, default=60.0)
    parser.add_argument("--max-voyage-hours", type=float, default=1000.0)
    args = parser.parse_args()
    if args.tolerance_nm <= 0 or args.min_voyages < 1:
        parser.error("--tolerance-nm must be positive and --min-voyages at least 1")
    positions = read_positions(args.positions, args.max_sog)
    calls = read_port_calls(args.port_calls)
    voyages = extract_voyages(calls, positions, args.max_voyage_hours)
    result = build_geojson(
        voyages, args.source_name, args.source_license, args.quality_status,
        args.tolerance_nm, args.min_voyages
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(
        f"Read {sum(map(len, positions.values()))} AIS positions and "
        f"{sum(map(len, calls.values()))} port calls; extracted {len(voyages)} "
        f"voyages; wrote {len(result['features'])} route variants to {args.output}"
    )
    if not result["features"]:
        print("No routes met --min-voyages. Check source coverage and input timestamps.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
