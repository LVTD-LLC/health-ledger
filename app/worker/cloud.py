"""One provider page per claim, with a durable cursor and encrypted OAuth tokens."""

import datetime as dt
import hashlib
import json
import os
import time
import uuid
import requests
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from imports import UTC, record
import renpho_sync
import hevy
from renpho import RenphoAPIError

# Collection ID, JSON field, value, unit, date field used for incremental reads.
GOOGLE_TYPES = [
    ("weight", "weight", "weightGrams", "g", "sample_time.physical_time"),
    ("height", "height", "heightMillimeters", "mm", "sample_time.physical_time"),
    ("body-fat", "bodyFat", "percentage", "%", "sample_time.physical_time"),
    (
        "daily-resting-heart-rate",
        "dailyRestingHeartRate",
        "beatsPerMinute",
        "bpm",
        "date",
    ),
    ("sleep", "sleep", "minutesAsleep", "min", "interval.end_time"),
    ("steps", "steps", "count", "steps", "interval.start_time"),
    ("distance", "distance", "millimeters", "mm", "interval.start_time"),
    ("exercise", "exercise", "activeDuration", "s", "interval.civil_start_time"),
    ("heart-rate", "heartRate", "beatsPerMinute", "bpm", "sample_time.physical_time"),
]
WITHINGS_TYPES = {
    1: ("Weight", "kg"),
    4: ("Height", "m"),
    5: ("Fat-free mass", "kg"),
    6: ("Body fat", "%"),
    8: ("Fat mass", "kg"),
    9: ("Diastolic blood pressure", "mmHg"),
    10: ("Systolic blood pressure", "mmHg"),
    11: ("Heart rate", "bpm"),
    12: ("Temperature", "°C"),
    54: ("Oxygen saturation", "%"),
    71: ("Body temperature", "°C"),
    73: ("Skin temperature", "°C"),
    76: ("Muscle mass", "kg"),
    77: ("Hydration", "kg"),
    88: ("Bone mass", "kg"),
}


def google_rows(points, spec):
    collection, field, value_key, unit, _ = spec
    for point in points:
        data = point.get(field, {})
        civil = data.get("date", {})
        when = data.get("sampleTime", {}).get("physicalTime") or data.get(
            "interval", {}
        ).get("startTime")
        if not when and civil:
            when = dt.date(civil["year"], civil["month"], civil["day"]).isoformat()
        value = data.get(value_key, data.get("summary", {}).get(value_key))
        if when is None or value is None:
            continue
        if collection == "exercise":
            value = str(value).removesuffix("s")
        name = data.get("displayName") or collection.replace("-", " ").capitalize()
        identity = point.get("name") or [collection, when, point.get("dataSource", {})]
        yield record(
            when,
            "Workout"
            if field == "exercise"
            else "Sleep"
            if field == "sleep"
            else "Measurement",
            name,
            value,
            unit,
            source="Google Health",
            identity=identity,
        )


def google_page(access, cursor):
    index = cursor.get("index", 0)
    spec = GOOGLE_TYPES[index]
    collection, _, _, _, time_field = spec
    url = (
        "https://health.googleapis.com/v4/users/me/dataTypes/"
        + collection
        + "/dataPoints"
    )
    params = {"pageSize": 25 if collection in ("exercise", "sleep") else 1000}
    if cursor.get("since"):
        date = cursor["since"]
        literal = (
            date
            if time_field == "date" or "civil" in time_field
            else date + "T00:00:00Z"
        )
        params["filter"] = f'{collection.replace("-", "_")}.{time_field} >= "{literal}"'
    if cursor.get("page"):
        params["pageToken"] = cursor["page"]
    r = requests.get(
        url,
        params=params,
        headers={"Authorization": "Bearer " + access},
        timeout=25,
        allow_redirects=False,
    )
    r.raise_for_status()
    data = r.json()
    next_page = data.get("nextPageToken")
    updated = dict(cursor)
    updated["page"] = next_page
    if not next_page:
        updated["index"] = index + 1
    done = updated.get("index") == len(GOOGLE_TYPES)
    if done:
        updated = {
            "since": (dt.datetime.now(UTC).date() - dt.timedelta(days=7)).isoformat()
        }
    return google_rows(data.get("dataPoints", []), spec), updated, done


def withings_rows(groups):
    for group in groups:
        when = dt.datetime.fromtimestamp(group["date"], UTC).isoformat()
        for meas in group["measures"]:
            key = meas["type"]
            name, unit = WITHINGS_TYPES.get(key, (f"Withings measure {key}", ""))
            value = meas["value"] * 10 ** meas["unit"]
            yield record(
                when,
                "Measurement",
                name,
                format(value, ".12g"),
                unit,
                source="Withings",
                identity=[group["grpid"], key],
            )


def withings_page(access, cursor):
    params = {
        "action": "getmeas",
        "category": 1,
        "lastupdate": cursor.get("since", 0),
        "offset": cursor.get("offset", 0),
    }
    started = cursor.get("started") or int(time.time())
    r = requests.post(
        "https://wbsapi.withings.net/measure",
        data=params,
        headers={"Authorization": "Bearer " + access},
        timeout=25,
        allow_redirects=False,
    )
    r.raise_for_status()
    response = r.json()
    if response.get("status") != 0:
        raise ValueError("Provider declined measurement request")
    data = response["body"]
    done = not data.get("more")
    updated = (
        {"since": started - 300}
        if done
        else {**cursor, "offset": data["offset"], "started": started}
    )
    return withings_rows(data["measuregrps"]), updated, done


def refresh(provider, tokens):
    prefix, url = {
        "google-health": ("GOOGLE_HEALTH", "https://oauth2.googleapis.com/token"),
        "withings": ("WITHINGS", "https://wbsapi.withings.net/v2/oauth2"),
        "hevy": ("HEVY", "https://hevy.com/api/token"),
    }[provider]
    if tokens.get("expires_at", 0) > time.time() + 60:
        return tokens
    params = {
        "grant_type": "refresh_token",
        "client_id": os.environ[prefix + "_CLIENT_ID"],
        "client_secret": os.environ[prefix + "_CLIENT_SECRET"],
        "refresh_token": tokens["refresh_token"],
    }
    if provider == "withings":
        params["action"] = "requesttoken"
    r = requests.post(url, data=params, timeout=15, allow_redirects=False)
    r.raise_for_status()
    reply = r.json()
    if provider == "withings":
        reply = reply["body"]
    if not reply.get("access_token"):
        raise ValueError("Provider requires reconnection")
    return {
        **tokens,
        **reply,
        "expires_at": time.time() + int(reply.get("expires_in", 3600)),
    }


def process(cur, save):
    cur.execute(
        "SELECT user_id,provider,credential,cursor_json FROM health_cloud_sources WHERE next_sync<=now() ORDER BY next_sync LIMIT 1 FOR UPDATE SKIP LOCKED"
    )
    item = cur.fetchone()
    if not item:
        return False
    owner, provider, encrypted, cursor_json = item
    delay = 1800
    status = "Sync interrupted · retry in 30 minutes. Reconnect if permission expired."
    try:
        aes = AESGCM(
            hashlib.sha256(os.environ["HEALTH_CONNECTION_KEY"].encode()).digest()
        )
        aad = uuid.UUID(str(owner)).bytes
        ciphertext = bytes(encrypted)
        tokens = json.loads(aes.decrypt(ciphertext[:12], ciphertext[12:], aad))

        def replace_credential(data):
            nonce = os.urandom(12)
            new_token = nonce + aes.encrypt(nonce, json.dumps(data).encode(), aad)
            cur.execute(
                "UPDATE health_cloud_sources SET credential=%s WHERE user_id=%s AND provider=%s",
                (new_token, owner, provider),
            )

        if provider == "renpho" and "password" in tokens:
            replace_credential(renpho_sync.exchange(tokens))
            cur.execute(
                "UPDATE health_cloud_sources SET next_sync=now(), status='Importing history' WHERE user_id=%s AND provider=%s",
                (owner, provider),
            )
            return True
        if provider != "renpho":
            refreshed = refresh(provider, tokens)
            if refreshed != tokens:
                replace_credential(refreshed)
            tokens = refreshed
        cur.execute("SAVEPOINT cloud_page")
        try:
            cursor = json.loads(cursor_json)
            if provider == "hevy":
                cursor, done = hevy.page(
                    cur, save, owner, tokens["access_token"], cursor
                )
            else:
                if provider == "renpho":
                    rows, cursor, done = renpho_sync.page(tokens, cursor)
                else:
                    fetch = {"google-health": google_page, "withings": withings_page}[
                        provider
                    ]
                    rows, cursor, done = fetch(tokens["access_token"], cursor)
                save(cur, owner, rows)
            cursor_json = json.dumps(cursor)
            delay = 21600 if done else 2
            status = (
                ("Synced · " + dt.datetime.now(UTC).isoformat(timespec="minutes"))
                if done
                else "Importing history"
            )
        except Exception:
            cur.execute("ROLLBACK TO SAVEPOINT cloud_page")
            raise
    except renpho_sync.ReconnectRequired:
        replace_credential({})
        cur.execute(
            "UPDATE health_cloud_sources SET next_sync='infinity', status='Reconnect RENPHO to resume importing.' WHERE user_id=%s AND provider=%s",
            (owner, provider),
        )
        return True
    except (requests.RequestException, RenphoAPIError, ValueError, TypeError, KeyError):
        pass
    cur.execute(
        "UPDATE health_cloud_sources SET status=%s,cursor_json=%s,next_sync=now()+%s*interval '1 second' WHERE user_id=%s AND provider=%s",
        (status, cursor_json, delay, owner, provider),
    )
    return True
