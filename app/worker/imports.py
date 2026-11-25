"""Translate personal exports into timestamped records with source identifiers."""

import csv
import io
import json
import re
import zipfile
from pathlib import PurePosixPath

from defusedxml import ElementTree as xml
import android_health

from records import UTC, record, timestamp

MAX_EXPANDED = 256 * 1024 * 1024


def apple(stream, source):
    for _, node in xml.iterparse(stream, events=("end",)):
        a = node.attrib
        if node.tag in ("Record", "Workout") and a.get("startDate"):
            workout = node.tag == "Workout"
            key = a.get("workoutActivityType" if workout else "type", "")
            key = re.sub(
                r"^HK(?:QuantityTypeIdentifier|CategoryTypeIdentifier|WorkoutActivityType|CharacteristicTypeIdentifier)",
                "",
                key,
            )
            title = re.sub(r"(?<=[a-z])(?=[A-Z])", " ", key)
            origin = a.get("sourceName") or source
            yield record(
                a["startDate"],
                "Workout"
                if workout
                else ("Sleep" if "Sleep" in key else "Measurement"),
                title,
                a.get("duration" if workout else "value", ""),
                a.get("durationUnit" if workout else "unit", ""),
                a.get("endDate", ""),
                origin,
                [
                    node.tag,
                    key,
                    origin,
                    a.get("startDate"),
                    a.get("endDate"),
                    a.get("value"),
                ],
            )
        node.clear()


def csv_records(stream, source):
    stream = io.TextIOWrapper(stream, encoding="utf-8-sig", newline="")
    sample = stream.read(8192)
    stream.seek(0)
    try:
        dialect = csv.Sniffer().sniff(sample, delimiters=",;\t")
    except csv.Error:
        dialect = csv.excel
    rows = csv.reader(stream, dialect)
    headers = next(rows, [])
    # Samsung exports often start with a metadata line before their header.
    if headers and headers[0].startswith("com.samsung.") and len(headers) < 5:
        headers = next(rows, [])
    headers = [
        h.rsplit(".", 1)[-1].strip() if h.startswith("com.samsung.") else h.strip()
        for h in headers
    ]

    def normalized(h):
        return re.sub(r"[^a-z0-9]+", " ", h.lower()).strip()

    keys = [normalized(h) for h in headers]
    for date_key in (
        "timestamp",
        "date time",
        "datetime",
        "start time",
        "start date",
        "activity date",
        "time of measurement",
        "measurement time",
        "day time",
        "measurement date",
        "time",
        "date",
    ):
        if date_key in keys:
            index = keys.index(date_key)
            break
    else:
        raise ValueError(
            "CSV needs a date, time, start_time, timestamp or activity date column."
        )
    for cells in rows:
        if not any(cells):
            continue
        row = dict(zip(keys, cells))
        when = cells[index]
        offset = row.get("time offset", "").removeprefix("UTC")
        if (
            re.fullmatch(r"[+-]\d{4}", offset)
            and not re.search(r"(Z|[+-]\d{2}:?\d{2})$", when)
            and ":" in when
        ):
            when += offset
        if (
            "date" in row
            and "time" in row
            and ":" in row["time"]
            and "-" not in row["time"]
            and "/" not in row["time"]
        ):
            when = row["date"] + " " + row["time"]
        if ("exercise title" in row or "exercise name" in row) and "reps" in row:
            exercise = row.get("exercise title") or row.get("exercise name")
            weight = (
                row.get("weight kg") or row.get("weight lbs") or row.get("weight") or ""
            )
            unit = (
                "kg"
                if row.get("weight kg")
                else "lb"
                if row.get("weight lbs")
                else row.get("weight unit", "")
            )
            number = row.get("set index") or row.get("set order") or ""
            note = " · ".join(
                v
                for v in (
                    row.get("title") or row.get("workout name"),
                    "Set " + number if number else "",
                    (weight + " " + unit).strip(),
                    row.get("set type"),
                    row.get("notes"),
                )
                if v
            )
            yield record(
                when,
                "Workout",
                exercise,
                row["reps"],
                "reps",
                note,
                source,
                [when, exercise, number, note, row["reps"]],
            )
        elif "activity name" in row and "activity type" in row:
            yield record(
                when,
                "Workout",
                row["activity name"],
                row.get("elapsed time", ""),
                "s",
                row["activity type"],
                source,
                ["strava", row.get("activity id") or when],
            )
        elif "metric" in row and "value" in row:
            yield record(
                when,
                row.get("kind") or "Measurement",
                row["metric"],
                row["value"],
                row.get("unit", ""),
                row.get("note", ""),
                source,
            )
        else:
            for idx, (label, value) in enumerate(zip(headers, cells)):
                if (
                    idx == index
                    or not label
                    or not value.strip()
                    or not re.fullmatch(r"[+-]?\d+(?:[.,]\d+)?%?", value.strip())
                ):
                    continue
                if keys[idx] in (
                    "id",
                    "user id",
                    "activity id",
                    "create time",
                    "update time",
                    "end time",
                    "start time",
                    "date",
                    "time",
                ):
                    continue
                unit = re.search(r"\(([^()]*)\)\s*$|\[([^\[\]]*)\]\s*$", label)
                name = label[: unit.start()].strip() if unit else label
                yield record(
                    when,
                    "Measurement",
                    name,
                    value,
                    (unit.group(1) or unit.group(2)) if unit else "",
                    source=source,
                )


def mobile_export(data, source):
    for metric in data.get("metrics", []):
        for point in metric["data"]:
            when = point.get("date") or point.get("start") or point.get("sleepStart")
            for field, value in point.items():
                if not when or type(value) not in (int, float):
                    continue
                title = (
                    metric["name"] if field == "qty" else metric["name"] + " " + field
                )
                yield record(
                    when,
                    "Sleep" if "sleep" in title else "Measurement",
                    title,
                    value,
                    metric.get("units", ""),
                    point.get("value", ""),
                    point.get("source") or source,
                    [metric["name"], field, when, point.get("end"), point.get("value")],
                )
    for group, kind in [
        ("workouts", "Workout"),
        ("medications", "Medicine"),
        ("stateOfMind", "Check-in"),
        ("symptoms", "Symptom"),
    ]:
        for row in data.get(group, []):
            when = next(
                (
                    row[k]
                    for k in (
                        "start",
                        "date",
                        "startDate",
                        "scheduledDate",
                        "time",
                        "logDate",
                    )
                    if row.get(k)
                ),
                None,
            )
            if when:
                name = (
                    row.get("name")
                    or row.get("medicationName")
                    or row.get("label")
                    or kind
                )
                detail = {
                    k: v
                    for k, v in row.items()
                    if isinstance(v, (str, float, int)) and k != "id"
                }
                yield record(
                    when,
                    kind,
                    name,
                    row.get("duration", row.get("doseQuantity", "")),
                    "s" if group == "workouts" else row.get("unit", ""),
                    json.dumps(detail, ensure_ascii=False),
                    source,
                    [group, row.get("id") or detail],
                )


def json_records(stream, source, filename):
    data = json.load(stream)
    if (
        isinstance(data, dict)
        and isinstance(data.get("app_version"), str)
        and isinstance(data.get("timestamp"), str)
        and "records" not in data
        and "data" not in data
    ):
        yield from android_health.rows(data)
        return
    if isinstance(data, dict) and data.get("resourceType") in ("Bundle", "Observation"):
        resources = (
            [v.get("resource", {}) for v in data.get("entry", [])]
            if data["resourceType"] == "Bundle"
            else [data]
        )
        for obs in resources:
            if obs.get("resourceType") != "Observation":
                continue
            when = (
                obs.get("effectiveDateTime")
                or obs.get("effectivePeriod", {}).get("start")
                or obs.get("issued")
            )
            if not when:
                continue
            for component in [obs, *obs.get("component", [])]:
                code = component.get("code", {})
                coding = next(iter(code.get("coding", [])), {})
                title = (
                    code.get("text")
                    or coding.get("display")
                    or coding.get("code")
                    or "Lab observation"
                )
                quantity = component.get("valueQuantity", {})
                value = quantity.get(
                    "value",
                    component.get(
                        "valueString",
                        component.get("valueCodeableConcept", {}).get("text"),
                    ),
                )
                if value is None:
                    continue
                ref = component.get("referenceRange") or obs.get("referenceRange")
                note = (
                    "Reference range: " + json.dumps(ref, ensure_ascii=False)
                    if ref
                    else ""
                )
                identity = [obs.get("id") or when, coding.get("code") or title, when]
                yield record(
                    when,
                    "Lab",
                    title,
                    value,
                    quantity.get("unit", quantity.get("code", "")),
                    note,
                    source,
                    identity,
                )
        return
    if isinstance(data, dict) and isinstance(data.get("records"), list):
        for r in data["records"]:
            yield record(
                r["timestamp"],
                r.get("kind", "Measurement"),
                r["metric"],
                r.get("value", ""),
                r.get("unit", ""),
                r.get("note", ""),
                r.get("source") or source,
                r.get("id"),
            )
        return
    if isinstance(data, dict) and isinstance(data.get("data"), dict):
        yield from mobile_export(data["data"], source)
        return
    if not isinstance(data, list):
        raise ValueError("Expected a Fitbit JSON array.")
    name = re.sub(r"[-_ ]?\d{4}-\d{2}-\d{2}.*$", "", PurePosixPath(filename).stem)
    for row in data:
        when = row.get("dateTime") or row.get("startTime") or row.get("date")
        if not when:
            continue
        if "value" in row:
            value = row["value"]
            if isinstance(value, dict):
                for metric, number in value.items():
                    if isinstance(number, (float, int)) and metric != "confidence":
                        yield record(
                            when,
                            "Measurement",
                            name + " " + metric,
                            number,
                            "bpm" if metric == "bpm" else "",
                            source=source,
                        )
            else:
                yield record(when, "Measurement", name, value, source=source)
        else:
            for metric in (
                "weight",
                "fat",
                "bmi",
                "duration",
                "minutesAsleep",
                "efficiency",
                "calories",
            ):
                if metric in row:
                    yield record(
                        when,
                        "Sleep" if "minutesAsleep" in row else "Measurement",
                        metric,
                        row[metric],
                        source=source,
                    )


def parse_file(filename, payload, source):
    stream = io.BytesIO(payload)
    ext = PurePosixPath(filename).suffix.lower()
    if ext == ".zip":
        with zipfile.ZipFile(stream) as archive:
            members = []
            for entry in archive.infolist():
                name = PurePosixPath(entry.filename).name.lower()
                if entry.is_dir() or entry.filename.startswith("__MACOSX/"):
                    continue
                if name == "export.xml":
                    members.append((entry, "apple"))
                elif name == "activities.csv":
                    members.append((entry, "strava"))
                elif (
                    name.startswith("com.samsung.")
                    and name.endswith(".csv")
                    and any(
                        term in name
                        for term in (
                            ".weight.",
                            ".heart_rate.",
                            ".step_count.",
                            ".pedometer_day_summary.",
                            ".blood_pressure.",
                            ".oxygen_saturation.",
                            ".sleep_stage.",
                            ".exercise.",
                        )
                    )
                ):
                    members.append((entry, "samsung"))
                elif name.endswith(".json") and re.match(
                    r"(heart_rate|steps|sleep|weight|distance|calories|altitude|resting_heart_rate)[-_ ]\d{4}",
                    name,
                ):
                    members.append((entry, "fitbit"))
            if (
                not members
                or len(members) > 5000
                or sum(f.file_size for f, _ in members) > MAX_EXPANDED
            ):
                raise ValueError(
                    "No supported export members, or archive exceeds processing limits."
                )
            for entry, kind in members:
                with archive.open(entry) as member:
                    if kind == "apple":
                        yield from apple(member, source)
                    elif kind == "fitbit":
                        yield from json_records(member, source, entry.filename)
                    else:
                        yield from csv_records(member, source)
    elif ext == ".xml":
        yield from apple(stream, source)
    elif ext == ".csv" or ext == ".tsv":
        yield from csv_records(stream, source)
    elif ext == ".json":
        yield from json_records(stream, source, filename)
    else:
        raise ValueError(
            "Supported imports are Apple Health XML/ZIP, CSV, TSV and Fitbit JSON."
        )
