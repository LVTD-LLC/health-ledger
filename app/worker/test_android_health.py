import json
import unittest

from imports import parse_file


class AndroidHealthTests(unittest.TestCase):
    def parse(self, **groups):
        raw = json.dumps(
            {"app_version": "3.0", "timestamp": "2026-11-01T10:00:00Z", **groups}
        ).encode()
        return list(parse_file("device-export.json", raw, "Device export"))

    def test_series_samples_sources_and_replay(self):
        meta = {"id": "hr-series", "data_origin": "com.sec.android.app.shealth"}
        first = {"time": "2026-11-01T10:00:00+01:00", "bpm": 70, "metadata": meta}
        second = {**first, "time": "2026-11-01T09:01:00Z", "bpm": 73}
        a, b = self.parse(heart_rate=[first, second])
        self.assertNotEqual(a[-1], b[-1])
        self.assertIn("shealth", a[-2])
        self.assertEqual(a[2:5], ("Heart rate", "70", "bpm"))
        [edited] = self.parse(
            heart_rate=[{**first, "time": "2026-11-01T09:00:00Z", "bpm": 69}]
        )
        self.assertEqual(edited[-1], a[-1])
        self.assertEqual(self.parse(test=True, heart_rate=[first]), [])

    def test_sleep_nutrition_and_pressure(self):
        at = "2026-11-01T09:00:00Z"
        rows = self.parse(
            sleep=[
                {
                    "session_end_time": at,
                    "duration_seconds": 3600,
                    "stages": [
                        {"start_time": at, "stage": "deep", "duration_seconds": 600}
                    ],
                }
            ],
            nutrition=[{"start_time": at, "protein_grams": 25, "calories": 430}],
            blood_pressure=[{"time": at, "systolic": 120, "diastolic": 78}],
        )
        self.assertEqual(
            {r[2:5] for r in rows},
            {
                ("Sleep", "3600", "s"),
                ("Sleep stage · deep", "600", "s"),
                ("Nutrition", "430", "kcal"),
                ("Nutrition · protein", "25", "g"),
                ("Systolic blood pressure", "120", "mmHg"),
                ("Diastolic blood pressure", "78", "mmHg"),
            },
        )
