"""Read only the connected RENPHO profile, reusing its single active session."""

import datetime as dt
import math

import requests
from renpho import RenphoAPIError, RenphoAuthError, RenphoClient, RenphoSessionError
from renpho.constants import GIRTH_METRICS, METRICS
from imports import UTC, record


class ReconnectRequired(ValueError):
    """Park this source until the owner explicitly signs in again."""


def exchange(credentials):
    client = RenphoClient(
        credentials["email"], credentials["password"], timeout=(4, 18)
    )
    try:
        client.login()
        session = client.export_session()
        if not session.get("user_id"):
            raise ReconnectRequired()
        return {"session": session}
    except (requests.RequestException, RenphoAPIError, ValueError, KeyError) as error:
        # A failed response may follow a successful login. Never repeat it unattended.
        raise ReconnectRequired() from error


def measurements(items, profile, tape):
    for item in items:
        if any(
            str(item[k]) != str(profile)
            for k in ("bUserId", "userId")
            if item.get(k) is not None
        ):
            continue
        raw = item.get("timeStamp") or item.get("time_stamp")
        try:
            instant = int(raw)
            when = dt.datetime.fromtimestamp(
                instant / 1000 if instant >= 10**12 else instant, UTC
            )
        except (TypeError, ValueError, OverflowError, OSError):
            continue
        for key, label, unit in GIRTH_METRICS if tape else METRICS:
            value = item.get(key)
            try:
                number = float(value)
            except (TypeError, ValueError):
                continue
            if not math.isfinite(number) or number == 0:
                continue
            if tape and unit == "cm":
                flag = str(item.get(key.replace("Value", "Unit"), 0))
                unit = {"0": "cm", "1": "in"}.get(flag, "")
            yield record(
                when,
                "Measurement",
                label,
                value,
                unit,
                source="RENPHO",
                identity=[
                    str(profile),
                    tape,
                    item.get("id"),
                    when.isoformat(),
                    item.get("scaleMac"),
                    key,
                ],
            )


def page(credentials, cursor):
    session = credentials["session"]
    client = RenphoClient(session["email"], "", timeout=(4, 18), auto_relogin=False)
    if not session.get("user_id") or not client.import_session(session):
        raise ReconnectRequired()
    tape = cursor.get("stage") == "tape"
    try:
        items = (
            client.get_girth_measurements() if tape else client.get_all_measurements()
        )
    except (RenphoSessionError, RenphoAuthError) as error:
        raise ReconnectRequired() from error
    return (
        measurements(items, session["user_id"], tape),
        {} if tape else {"stage": "tape"},
        tape,
    )
