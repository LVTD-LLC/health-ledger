import hashlib
import json
import os
import uuid
from unittest import TestCase, mock, skipUnless

import psycopg2
import requests
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from renpho import RenphoSessionError
import cloud
import hevy
import renpho_sync
from worker import save


class MetricTests(TestCase):
    def test_renpho_profile_units_and_stable_identity(self):
        item = {
            "timeStamp": 1700000000000,
            "id": 1,
            "userId": "42",
            "customValue1": 15.5,
            "customUnit1": 1,
            "waistValue": 0,
        }
        (row,) = renpho_sync.measurements([item, {**item, "userId": 99}], 42, True)
        self.assertEqual(row[2:5], ("Custom 1", "15.5", "in"))
        (edited,) = renpho_sync.measurements(
            [{**item, "timeStamp": 1700000000, "customValue1": 16}], 42, True
        )
        self.assertEqual(row[-1], edited[-1])

    @mock.patch("cloud.requests.post")
    def test_refresh_hevy_rotates_token_at_fixed_endpoint(self, post):
        with mock.patch.dict(
            os.environ, HEVY_CLIENT_ID="client", HEVY_CLIENT_SECRET="secret"
        ):
            post.return_value.json.return_value = {
                "access_token": "new",
                "refresh_token": "rotated",
            }
            tokens = cloud.refresh("hevy", {"refresh_token": "old"})
        self.assertEqual(tokens["refresh_token"], "rotated")
        self.assertEqual(post.call_args.args, ("https://hevy.com/api/token",))
        self.assertEqual(post.call_args.kwargs["data"]["refresh_token"], "old")


@skipUnless(os.environ.get("WORKER_TEST_DATABASE_URL"), "requires Postgres")
class ConnectionTests(TestCase):
    def setUp(self):
        self.conn = psycopg2.connect(os.environ["WORKER_TEST_DATABASE_URL"])
        self.cur = self.conn.cursor()
        self.owner = str(uuid.uuid4())
        self.cur.execute(
            "INSERT INTO users(id,email,password_hash) VALUES(%s,%s,'test')",
            (self.owner, self.owner + "@example.test"),
        )
        self.env = mock.patch.dict(os.environ, HEALTH_CONNECTION_KEY="fixture" * 8)
        self.env.start()
        self.aes = AESGCM(
            hashlib.sha256(os.environ["HEALTH_CONNECTION_KEY"].encode()).digest()
        )
        self.aad = uuid.UUID(self.owner).bytes

    def tearDown(self):
        self.conn.rollback()
        self.conn.close()
        self.env.stop()

    def seed(self):
        nonce = os.urandom(12)
        cipher = nonce + self.aes.encrypt(
            nonce, b'{"email":"me@example.test","password":"fixture"}', self.aad
        )
        self.cur.execute(
            "INSERT INTO health_cloud_sources(user_id,provider,credential) VALUES(%s,'renpho',%s)",
            (self.owner, cipher),
        )

    def state(self):
        self.cur.execute(
            "SELECT credential,status,next_sync='infinity' FROM health_cloud_sources WHERE user_id=%s",
            (self.owner,),
        )
        blob, status, parked = self.cur.fetchone()
        blob = bytes(blob)
        return (
            json.loads(self.aes.decrypt(blob[:12], blob[12:], self.aad)),
            status,
            parked,
        )

    def tick(self):
        self.cur.execute(
            "UPDATE health_cloud_sources SET next_sync=now() WHERE user_id=%s",
            (self.owner,),
        )
        self.assertTrue(cloud.process(self.cur, save))

    @mock.patch("renpho_sync.RenphoClient")
    def test_exchange_backfill_replay_and_expired_session(self, client):
        self.seed()
        api = client.return_value
        session = {"email": "me@example.test", "user_id": 42, "token": "test"}
        api.export_session.return_value = session
        api.get_all_measurements.return_value = [
            {
                "id": 1,
                "bUserId": 42,
                "timeStamp": 1700000000,
                "weight": 75,
                "bodyfat": 18,
            },
            {"id": 2, "bUserId": 17, "timeStamp": 1700000001, "weight": 90},
        ]
        api.get_girth_measurements.return_value = [
            {"id": 1, "userId": "42", "timeStamp": 1700000000, "waistValue": 80}
        ]
        self.tick()
        self.assertEqual(self.state()[0], {"session": session})
        for _ in range(4):
            self.tick()
        self.cur.execute(
            "SELECT title,value FROM health_records WHERE user_id=%s ORDER BY title",
            (self.owner,),
        )
        self.assertEqual(
            self.cur.fetchall(), [("Body Fat", "18"), ("Waist", "80"), ("Weight", "75")]
        )
        api.get_all_measurements.assert_called_with()
        api.login.assert_called_once_with()
        self.assertTrue(self.state()[1].startswith("Synced"))
        api.get_all_measurements.side_effect = requests.Timeout()
        self.tick()
        self.assertFalse(self.state()[2])
        self.assertEqual(self.state()[0], {"session": session})
        api.get_all_measurements.side_effect = RenphoSessionError(
            "test", 403, "expired"
        )
        self.tick()
        self.assertEqual(
            self.state(), ({}, "Reconnect RENPHO to resume importing.", True)
        )
        self.assertFalse(cloud.process(self.cur, save))
        api.login.assert_called_once_with()

    @mock.patch("renpho_sync.RenphoClient")
    def test_ambiguous_login_failure_discards_password(self, client):
        self.seed()
        client.return_value.login.side_effect = requests.Timeout()
        self.tick()
        self.assertEqual(self.state()[0], {})
        self.assertTrue(self.state()[2])

    @mock.patch("hevy.requests.get")
    def test_hevy_backfill_and_edited_deleted_sets(self, get):
        wid = str(uuid.uuid4())
        exercise = {
            "index": 0,
            "title": "Squat",
            "sets": [{"index": 0, "reps": 5, "weight_kg": 80}, {"index": 1, "reps": 4}],
        }
        workout = {
            "id": wid,
            "title": "Morning",
            "start_time": "2026-01-01T08:00Z",
            "end_time": "2026-01-01T09:00Z",
            "exercises": [exercise],
        }
        get.return_value.json.return_value = {"page_count": 2, "workouts": [workout]}
        cursor, done = hevy.page(self.cur, save, self.owner, "test", {})
        self.assertFalse(done)
        get.return_value.json.return_value = {"page_count": 2, "workouts": []}
        cursor, done = hevy.page(self.cur, save, self.owner, "test", cursor)
        self.assertTrue(done)
        self.assertEqual(get.call_args.kwargs["params"]["page"], 2)
        self.cur.execute(
            "SELECT count(*) FROM health_records WHERE user_id=%s", (self.owner,)
        )
        self.assertEqual(self.cur.fetchone()[0], 3)
        exercise["sets"] = [{"index": 0, "reps": 6, "weight_kg": 85}]
        get.return_value.json.return_value = {
            "page_count": 1,
            "events": [{"type": "updated", "workout": workout}],
        }
        hevy.page(self.cur, save, self.owner, "test", cursor)
        self.assertTrue(get.call_args.args[0].endswith("/events"))
        self.cur.execute(
            "SELECT value,note FROM health_records WHERE user_id=%s AND title='Squat'",
            (self.owner,),
        )
        [(value, note)] = self.cur.fetchall()
        self.assertEqual(value, "6")
        self.assertIn("85 kg", note)
        get.return_value.json.return_value = {
            "page_count": 1,
            "events": [{"type": "deleted", "id": wid}],
        }
        hevy.page(self.cur, save, self.owner, "test", cursor)
        self.cur.execute(
            "SELECT count(*) FROM health_records WHERE user_id=%s", (self.owner,)
        )
        self.assertEqual(self.cur.fetchone()[0], 0)
