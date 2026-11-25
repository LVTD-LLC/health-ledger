import io
import unittest
import zipfile
from imports import parse_file
from documents import extract


class ImportTests(unittest.TestCase):
    def test_apple_zip_source_offset_and_identity(self):
        raw = b'<HealthData><Record type="HKQuantityTypeIdentifierBodyMass" sourceName="My scale" unit="kg" value="70" startDate="2025-08-01 12:00:00 +0200"/></HealthData>'
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w") as z:
            z.writestr("apple_health_export/export.xml", raw)
        (a,) = parse_file("export.zip", out.getvalue(), "Health")
        (b,) = parse_file("export.xml", raw, "Health")
        self.assertEqual(a, b)
        self.assertEqual(
            (a[0].hour, a[2], a[3], a[4], a[6]),
            (10, "Body Mass", "70", "kg", "My scale"),
        )

    def test_wide_csv_keeps_units_and_columns(self):
        rows = list(
            parse_file(
                "weights.csv",
                b"Time of Measurement,Weight (kg),BMI\n2026-01-02,80.1,22.0\n",
                "RENPHO",
            )
        )
        self.assertEqual(
            [r[2:5] for r in rows], [("Weight", "80.1", "kg"), ("BMI", "22.0", "")]
        )

    def test_fitbit_heart_rate(self):
        rows = list(
            parse_file(
                "heart_rate-2026-01-01.json",
                b'[{"dateTime":"01/01/26 06:04:00","value":{"bpm":62,"confidence":3}}]',
                "Fitbit",
            )
        )
        self.assertEqual(rows[0][3:5], ("62", "bpm"))
        self.assertEqual(len(rows), 1)

    def test_document_text_and_unsupported_original(self):
        self.assertEqual(extract("lab.txt", b"Example 8 g/L")[0], "Example 8 g/L")
        self.assertEqual(extract("sample.bin", b"\xff")[0], "")


if __name__ == "__main__":
    unittest.main()


class DeviceExportTests(unittest.TestCase):
    def test_measurements_and_device_replays(self):
        payload = b'{"data":{"metrics":[{"name":"heart_rate","units":"bpm","data":[{"date":"2026-01-02 07:00:00 +0100","Avg":60,"Max":70,"source":"Watch"}]}]}}'
        rows = list(parse_file("export.json", payload, "Phone"))
        self.assertEqual(
            [r[2:5] for r in rows],
            [("heart_rate Avg", "60", "bpm"), ("heart_rate Max", "70", "bpm")],
        )
        self.assertEqual(rows, list(parse_file("export.json", payload, "Phone")))

    def test_generic_record_identity_survives_correction(self):
        payload = b'{"records":[{"id":"a","timestamp":"2026-01-01","metric":"Weight","value":70,"source":"Scale"}]}'
        (a,) = parse_file("sync.json", payload, "Device")
        (b,) = parse_file("sync.json", payload.replace(b":70", b":71"), "Device")
        self.assertEqual(a[-1], b[-1])


class TrainingAndLabTests(unittest.TestCase):
    def test_exercise_sets(self):
        data = b"start_time,exercise_title,set_index,reps,weight_kg\n2026-01-02,Squat,1,5,40\n"
        (row,) = parse_file("workouts.csv", data, "Hevy")
        self.assertEqual(row[1:5], ("Workout", "Squat", "5", "reps"))
        self.assertIn("40 kg", row[5])

    def test_fhir_component_with_range(self):
        data = b'{"resourceType":"Observation","id":"lab1","effectiveDateTime":"2026-01-02","component":[{"code":{"text":"Sample"},"valueQuantity":{"value":3,"unit":"g/L"},"referenceRange":[{"low":{"value":1}}]}]}'
        (row,) = parse_file("lab.json", data, "Clinic")
        self.assertEqual(row[1:5], ("Lab", "Sample", "3", "g/L"))
        self.assertIn("Reference range", row[5])
