"""Hevy OAuth workout history and subsequent edit/deletion events."""

import datetime as dt
from uuid import UUID

import requests
from imports import UTC, record, timestamp


def workout_rows(workout, prefix):
    when = workout["start_time"]
    duration = (timestamp(workout["end_time"]) - timestamp(when)).total_seconds()
    title = workout.get("title") or "Workout"
    header = record(
        when,
        "Workout",
        title,
        format(duration, "g"),
        "s",
        workout.get("description") or "",
        "Hevy",
    )
    yield (*header[:-1], prefix + "workout")
    for exercise in workout["exercises"]:
        for entry in exercise["sets"]:
            measures = [
                (entry.get(k), u)
                for k, u in (
                    ("reps", "reps"),
                    ("weight_kg", "kg"),
                    ("distance_meters", "m"),
                    ("duration_seconds", "s"),
                    ("rpe", "RPE"),
                    ("custom_metric", "custom metric"),
                )
                if entry.get(k) is not None
            ]
            value, unit = measures[0] if measures else ("", "")
            detail = ", ".join(f"{v} {u}" for v, u in measures)
            note = " · ".join(
                filter(None, (title, entry.get("type"), detail, exercise.get("notes")))
            )
            row = record(when, "Workout", exercise["title"], value, unit, note, "Hevy")
            yield (*row[:-1], f"{prefix}{exercise['index']}/{entry['index']}")


def page(cur, save, owner, access, cursor):
    page = cursor.get("page", 1)
    started = (
        cursor.get("started")
        or (dt.datetime.now(UTC) - dt.timedelta(minutes=5)).isoformat()
    )
    incremental = "since" in cursor
    params = {"page": page, "pageSize": 10}
    url = "https://api.hevyapp.com/v1/workouts"
    if incremental:
        params["since"] = cursor["since"]
        url += "/events"
    response = requests.get(
        url,
        params=params,
        headers={"Authorization": "Bearer " + access},
        timeout=25,
        allow_redirects=False,
    )
    response.raise_for_status()
    data = response.json()
    events = (
        data["events"]
        if incremental
        else ({"type": "updated", "workout": w} for w in data["workouts"])
    )
    for event in events:
        deleted = event["type"] == "deleted"
        if not deleted and event["type"] != "updated":
            raise ValueError("Unknown Hevy event")
        workout_id = UUID(event["id"] if deleted else event["workout"]["id"])
        prefix = f"hevy/{workout_id}/"
        rows = [] if deleted else list(workout_rows(event["workout"], prefix))
        save(cur, owner, rows)
        # Keep row IDs for unchanged sets. Remove only sets gone from this owner's workout.
        cur.execute(
            """DELETE FROM health_records WHERE user_id=%s AND source='Hevy'
                       AND starts_with(external_key,%s) AND NOT (external_key=ANY(%s::text[]))""",
            (owner, prefix, [r[-1] for r in rows]),
        )
    done = page >= data["page_count"]
    return {"since": started} if done else {
        **cursor,
        "page": page + 1,
        "started": started,
    }, done
