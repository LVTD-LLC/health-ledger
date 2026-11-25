"""Shared timeline record identity and timestamp normalization."""

import datetime as dt
import hashlib
import json
import re

UTC = dt.timezone.utc


def timestamp(raw):
    value = str(raw).strip()
    if re.fullmatch(r"\d{13}", value):
        return dt.datetime.fromtimestamp(int(value) / 1000, UTC)
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        for pattern in (
            "%Y-%m-%d %H:%M:%S %z",
            "%b %d, %Y, %I:%M:%S %p",
            "%m/%d/%y %H:%M:%S",
            "%d %b %Y, %H:%M",
            "%d %b %Y, %H:%M:%S",
            "%Y/%m/%d %H:%M:%S",
            "%Y/%m/%d",
            "%m/%d/%Y %H:%M:%S",
            "%m/%d/%Y %I:%M:%S %p",
            "%m/%d/%Y",
        ):
            try:
                parsed = dt.datetime.strptime(value, pattern)
                break
            except ValueError:
                continue
        else:
            raise ValueError("Unrecognized timestamp")
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


def record(when, kind, name, value="", unit="", note="", source="", identity=None):
    when = timestamp(when).astimezone(UTC)
    fields = (
        when,
        kind,
        str(name)[:160],
        str(value)[:160],
        str(unit)[:64],
        str(note)[:4000],
        str(source)[:160],
    )
    digest = hashlib.sha256(
        json.dumps(identity or fields, default=str, ensure_ascii=False).encode()
    ).hexdigest()
    return (*fields, digest)
