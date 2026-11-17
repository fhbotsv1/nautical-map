import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ais_route_builder import (  # noqa: E402
    PortCall, Position, Voyage, build_geojson, cluster_voyages, haversine_nm
)


def port(mmsi, name, country, lat, lon, when):
    stamp = datetime.fromisoformat(when).replace(tzinfo=timezone.utc)
    return PortCall(mmsi, name, country, "", lat, lon, stamp, stamp)


def voyage(mmsi, offset=0.0):
    origin = port(mmsi, "Port A", "Country A", 10, 20, "2025-01-01T00:00:00")
    destination = port(mmsi, "Port B", "Country B", 10, 24, "2025-01-03T00:00:00")
    points = [
        Position(datetime(2025, 1, 1, 12, tzinfo=timezone.utc), 10 + offset, 21, 12, "Container"),
        Position(datetime(2025, 1, 2, 12, tzinfo=timezone.utc), 10 + offset, 23, 12, "Container"),
    ]
    return Voyage(origin, destination, points)


class RouteBuilderTests(unittest.TestCase):
    def test_haversine_distance_is_positive(self):
        self.assertGreater(haversine_nm((0, 0), (0, 1)), 50)

    def test_nearby_tracks_cluster(self):
        clusters = cluster_voyages([voyage("1"), voyage("2", 0.01)], 20)
        self.assertEqual(len(clusters), 1)
        self.assertEqual(len(clusters[0]), 2)

    def test_separate_tracks_remain_variants(self):
        clusters = cluster_voyages([voyage("1"), voyage("2", 2)], 20)
        self.assertEqual(len(clusters), 2)

    def test_geojson_matches_android_import_contract(self):
        result = build_geojson(
            [voyage("1"), voyage("2")], "Test fixture only",
            "Synthetic test data; not for navigation", "UNVERIFIED", 20, 2
        )
        self.assertEqual(result["type"], "FeatureCollection")
        feature = result["features"][0]
        props = feature["properties"]
        for key in (
            "source_name", "origin_name", "origin_country", "origin_latitude",
            "origin_longitude", "destination_name", "destination_country",
            "destination_latitude", "destination_longitude"
        ):
            self.assertIn(key, props)
        self.assertEqual(feature["geometry"]["type"], "LineString")
        self.assertEqual(props["ais_sample_count"], 4)
        self.assertEqual(props["quality_status"], "UNVERIFIED")
        self.assertEqual(props["point_type"], "AIS_DERIVED")

    def test_minimum_voyage_count_filters_singletons(self):
        result = build_geojson(
            [voyage("1")], "Test", "Test only", "UNVERIFIED", 20, 2
        )
        self.assertEqual(result["features"], [])


if __name__ == "__main__":
    unittest.main()
