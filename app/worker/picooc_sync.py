"""Read the connected primary profile through the pinned SmartScaleConnect client."""

import json
import math
import subprocess

from records import UTC, record, timestamp

METRICS = {
    "Weight": ("Weight", "kg"),
    "BMI": ("BMI", "kg/m²"),
    "BodyFat": ("Body fat", "%"),
    "BodyWater": ("Body water", "%"),
    "BoneMass": ("Bone mass", "kg"),
    "VisceralFat": ("Visceral fat", "level"),
    "MetabolicAge": ("Metabolic age", "years"),
    "BasalMetabolism": ("Basal metabolic rate", "kcal/day"),
    "SkeletalMuscleMass": ("Skeletal muscle mass", "kg"),
}


class LoginRejected(ValueError):
    pass


def measurements(account, samples):
    for sample in samples:
        when = timestamp(sample["Date"]).astimezone(UTC)
        for key, (name, unit) in METRICS.items():
            value = sample.get(key)
            if (
                type(value) not in (float, int)
                or not math.isfinite(value)
                or value <= 0
            ):
                continue
            yield record(
                when,
                "Measurement",
                name,
                value,
                unit,
                source="PICOOC",
                identity=[account, sample.get("Source", ""), when.isoformat(), key],
            )


def history(credentials):
    try:
        result = subprocess.run(
            ["health-ledger-picooc"],
            input=json.dumps(credentials).encode(),
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=90,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as err:
        raise ValueError("PICOOC client unavailable") from err
    if result.returncode == 2:
        raise LoginRejected()
    if result.returncode != 0:
        raise ValueError("PICOOC sync interrupted")
    # The client's empty history is a nil Go slice (JSON null).
    samples = json.loads(result.stdout)
    if samples is not None and not isinstance(samples, list):
        raise ValueError("Unexpected PICOOC history")
    return measurements(credentials["email"].casefold(), samples or [])
