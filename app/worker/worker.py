"""Durable account-owned work. Each claim and its results commit together."""

import datetime as dt
from contextlib import closing
import hashlib
import logging
import os
import time
import uuid
from itertools import islice

import psycopg2
from psycopg2.extras import execute_values
import requests
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from documents import extract
import cloud
from imports import UTC, parse_file, record

LOG = logging.getLogger("health-worker")
UPSERT = """INSERT INTO health_records(user_id,occurred_at,kind,title,value,unit,note,source,external_key)
VALUES %s ON CONFLICT(user_id,source,external_key) DO UPDATE
SET occurred_at=EXCLUDED.occurred_at,title=EXCLUDED.title,value=EXCLUDED.value,unit=EXCLUDED.unit,note=EXCLUDED.note"""


def save(cur, owner, rows):
    count = 0
    rows = iter(rows)
    while batch := list(islice(rows, 500)):
        # Identical samples in a single export may share an identity.
        distinct = {(r[-2], r[-1]): (owner, *r) for r in batch}
        execute_values(cur, UPSERT, list(distinct.values()), page_size=500)
        count += len(distinct)
        if count > 2_000_000:
            raise ValueError("Import exceeds two million records.")
    return count


def file_job(cur):
    cur.execute(
        "SELECT id,user_id,name,purpose,source,content FROM health_files WHERE status='Queued' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED"
    )
    item = cur.fetchone()
    if not item:
        return False
    file_id, owner, name, purpose, source, payload = item
    cur.execute("SAVEPOINT file_work")
    try:
        if purpose == "import":
            count = save(cur, owner, parse_file(name, bytes(payload), source))
            status = (
                f"Processed {count:,} records"
                if count
                else "No supported records in file"
            )
            text = ""
        else:
            text, status = extract(name, bytes(payload))
            text = text.replace("\x00", "")
    except Exception as err:
        cur.execute("ROLLBACK TO SAVEPOINT file_work")
        LOG.warning("File processing failed (%s)", type(err).__name__)
        text, status = (
            "",
            "Original saved · processing failed. Check the file format and date columns.",
        )
    cur.execute(
        "UPDATE health_files SET status=%s,extracted=%s WHERE id=%s",
        (status, text, file_id),
    )
    return True


def intervals(token, oldest, newest):
    url = "https://intervals.icu/api/v1/athlete/0/"

    def get(path):
        r = requests.get(
            url + path,
            params={"oldest": oldest, "newest": newest},
            auth=("API_KEY", token),
            timeout=(5, 30),
            allow_redirects=False,
        )
        r.raise_for_status()
        return r.json()

    units = {
        "weight": "kg",
        "restingHR": "bpm",
        "hrv": "ms",
        "sleepSecs": "s",
        "steps": "steps",
        "systolic": "mmHg",
        "diastolic": "mmHg",
        "spO2": "%",
    }
    for day in get("wellness"):
        for key, value in day.items():
            if key not in ("id", "athleteId") and type(value) in (float, int):
                yield record(
                    day["id"],
                    "Measurement",
                    key,
                    value,
                    units.get(key, ""),
                    source="Intervals.icu",
                    identity=[day["id"], key],
                )
    for act in get("activities"):
        when = act.get("start_date") or act.get("start_date_local")
        if when and act.get("id"):
            yield record(
                when,
                "Workout",
                act.get("name") or act.get("type") or "Activity",
                act.get("moving_time") or 0,
                "s",
                act.get("type") or "",
                "Intervals.icu",
                [act["id"], "activity"],
            )
            for key, unit in [
                ("distance", "m"),
                ("average_heartrate", "bpm"),
                ("max_heartrate", "bpm"),
                ("total_elevation_gain", "m"),
            ]:
                if act.get(key) is not None:
                    yield record(
                        when,
                        "Workout",
                        key,
                        act[key],
                        unit,
                        source="Intervals.icu",
                        identity=[act["id"], key],
                    )


def sync_job(cur):
    cur.execute(
        "SELECT user_id,credential,backfill_before FROM health_connections WHERE next_sync<=now() ORDER BY next_sync LIMIT 1 FOR UPDATE SKIP LOCKED"
    )
    item = cur.fetchone()
    if not item:
        return False
    owner, credential, cutoff = item
    today = dt.datetime.now(UTC).date()
    floor = dt.date(1970, 1, 1)
    backfilling = cutoff != floor
    newest = cutoff or today
    oldest = (
        max(floor, newest - dt.timedelta(days=179))
        if backfilling
        else today - dt.timedelta(days=7)
    )
    if not backfilling:
        newest = today
    cur.execute("SAVEPOINT sync_work")
    try:
        encrypted = bytes(credential)
        key = hashlib.sha256(os.environ["HEALTH_CONNECTION_KEY"].encode()).digest()
        token = (
            AESGCM(key)
            .decrypt(encrypted[:12], encrypted[12:], uuid.UUID(str(owner)).bytes)
            .decode()
        )
        count = save(cur, owner, intervals(token, oldest, newest))
        cutoff = max(floor, oldest - dt.timedelta(days=1)) if backfilling else floor
        delay = 60 if cutoff > floor else 21600
        status = (
            f"Imported through {oldest} · {count} records in latest batch"
            if cutoff > floor
            else f"Last sync {today} · refresh every 6 hours"
        )
    except Exception as error:
        cur.execute("ROLLBACK TO SAVEPOINT sync_work")
        status = "Could not sync · will retry in one hour. Check your key and provider access."
        delay = 3600
        LOG.warning("Source sync failed (%s)", type(error).__name__)
    cur.execute(
        "UPDATE health_connections SET backfill_before=%s,status=%s,next_sync=now()+%s*interval '1 second' WHERE user_id=%s",
        (cutoff, status, delay, owner),
    )
    return True


def remind_job(cur):
    cur.execute(
        "SELECT r.user_id,u.email FROM health_reminders r JOIN users u ON u.id=r.user_id WHERE r.next_send<=now() ORDER BY r.next_send LIMIT 1 FOR UPDATE OF r SKIP LOCKED"
    )
    item = cur.fetchone()
    if not item:
        return False
    owner, email = item
    url = "https://api.mailgun.net/v3/" + os.environ["MAILGUN_DOMAIN"] + "/messages"
    r = requests.post(
        url,
        auth=("api", os.environ["MAILGUN_SENDING_KEY"]),
        data={
            "from": os.environ["MAIL_FROM"],
            "to": email,
            "subject": "Your daily check-in",
            "text": "Take a moment to record how you feel. "
            + os.environ["SITE_URL"]
            + "/dashboard#entry\nChange reminders in your journal.",
        },
        timeout=12,
        allow_redirects=False,
    )
    if r.ok:
        cur.execute(
            "UPDATE health_reminders SET next_send=next_send + (floor(extract(epoch from (now()-next_send))/86400)+1)*interval '1 day' WHERE user_id=%s",
            (owner,),
        )
    else:
        cur.execute(
            "UPDATE health_reminders SET next_send=now()+interval '1 hour' WHERE user_id=%s",
            (owner,),
        )
    return True


def cloud_job(cur):
    return cloud.process(cur, save)


def main():
    while True:
        worked = False
        try:
            with closing(psycopg2.connect(os.environ["DATABASE_URL"])) as conn:
                for job in (file_job, sync_job, cloud_job, remind_job):
                    with conn, conn.cursor() as cur:
                        worked = job(cur) or worked
        except Exception as error:
            LOG.warning("Worker waiting (%s)", type(error).__name__)
        time.sleep(1 if worked else 10)


if __name__ == "__main__":
    main()
