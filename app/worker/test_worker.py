import os
import unittest
import uuid
import psycopg2
from cloud import google_rows, GOOGLE_TYPES, withings_rows
from worker import file_job


class CloudTests(unittest.TestCase):
    def test_scaled_withings_measure(self):
        (row,) = withings_rows(
            [
                {
                    "grpid": 8,
                    "date": 1680000000,
                    "measures": [{"type": 1, "value": 72400, "unit": -3}],
                }
            ]
        )
        self.assertEqual(row[2:5], ("Weight", "72.4", "kg"))

    def test_google_weight_has_units_and_time(self):
        (row,) = google_rows(
            [
                {
                    "name": "points/example",
                    "weight": {
                        "sampleTime": {"physicalTime": "2025-01-01T00:00:00Z"},
                        "weightGrams": 71234,
                    },
                }
            ],
            GOOGLE_TYPES[0],
        )
        self.assertEqual(row[2:5], ("Weight", "71234", "g"))


@unittest.skipUnless(
    os.environ.get("WORKER_TEST_DATABASE_URL"),
    "Set WORKER_TEST_DATABASE_URL for database tests",
)
class WorkerTests(unittest.TestCase):
    def setUp(self):
        self.conn = psycopg2.connect(os.environ["WORKER_TEST_DATABASE_URL"])
        self.cur = self.conn.cursor()
        self.owner = str(uuid.uuid4())
        self.cur.execute(
            "INSERT INTO users(id,email,password_hash) VALUES(%s,%s,'test')",
            (self.owner, self.owner + "@example.test"),
        )

    def tearDown(self):
        self.conn.rollback()
        self.conn.close()

    def test_upload_processing_and_repeated_batches(self):
        raw = b"timestamp,metric,value,unit\n2026-01-02,Weight,80,kg"
        for _ in range(2):
            self.cur.execute(
                "INSERT INTO health_files(user_id,name,purpose,source,content) VALUES(%s,'a.csv','import','Scale',%s)",
                (self.owner, raw),
            )
            self.assertTrue(file_job(self.cur))
        self.cur.execute(
            "SELECT count(*), max(value) FROM health_records WHERE user_id=%s",
            (self.owner,),
        )
        self.assertEqual(self.cur.fetchone(), (1, "80"))
        self.cur.execute(
            "INSERT INTO health_files(user_id,name,purpose,source,content) VALUES(%s,'lab.txt','document','',%s)",
            (self.owner, b"Example laboratory result"),
        )
        self.assertTrue(file_job(self.cur))
        self.cur.execute(
            "SELECT extracted FROM health_files WHERE user_id=%s AND purpose='document'",
            (self.owner,),
        )
        self.assertEqual(self.cur.fetchone()[0], "Example laboratory result")
