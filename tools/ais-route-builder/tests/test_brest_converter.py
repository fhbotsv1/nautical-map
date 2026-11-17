import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from brest_dataset_to_geojson import convert_dataset, haversine_nm  # noqa: E402


class BrestConverterTests(unittest.TestCase):
    def make_dataset(self, root: Path):
        (root / "nomen.csv").write_text(
            "route|originport|destinationport|length\n"
            "R_01|PORT_A|PORT_B|18520\n"
            "R_02|PORT_A|OCEAN_NORTH|20000\n",
            encoding="utf-8",
        )
        (root / "poi.csv").write_text(
            "portname|offname|longitude|latitude|locode\n"
            "PORT_A|Port A|1.0|2.0|FRAAA\n"
            "PORT_B|Port B|2.0|3.0|FRBBB\n",
            encoding="utf-8",
        )
        (root / "prototypes.csv").write_text(
            "route|pointnumber|longitude|latitude\n"
            "R_01|2|1.9|2.9\n"
            "R_01|1|1.1|2.1\n"
            "R_02|1|1.0|2.0\n"
            "R_02|2|1.5|2.5\n",
            encoding="utf-8",
        )

    def test_exports_known_port_route_and_skips_offshore_route(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.make_dataset(root)
            result, warnings = convert_dataset(root)
            self.assertEqual(len(result["features"]), 1)
            feature = result["features"][0]
            props = feature["properties"]
            self.assertEqual(props["source_route_id"], "R_01")
            self.assertEqual(props["origin_unlocode"], "FRAAA")
            self.assertEqual(props["destination_unlocode"], "FRBBB")
            self.assertEqual(props["distance_nm"], 10.0)
            self.assertEqual(props["point_type"], "RESEARCH_PROTOTYPE")
            self.assertEqual(props["quality_status"], "UNVERIFIED")
            self.assertEqual(feature["geometry"]["coordinates"][0], [1.1, 2.1])
            self.assertEqual(feature["geometry"]["coordinates"][-1], [1.9, 2.9])
            self.assertTrue(any("R_02 skipped" in warning for warning in warnings))
            json.dumps(result)

    def test_endpoint_gap_filter(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.make_dataset(root)
            result, warnings = convert_dataset(root, max_endpoint_gap_nm=1)
            self.assertEqual(result["features"], [])
            self.assertTrue(any("endpoint gap exceeds" in warning for warning in warnings))

    def test_haversine_zero_and_known_distance(self):
        self.assertAlmostEqual(haversine_nm((1, 2), (1, 2)), 0.0)
        self.assertGreater(haversine_nm((0, 0), (1, 0)), 50)


if __name__ == "__main__":
    unittest.main()
