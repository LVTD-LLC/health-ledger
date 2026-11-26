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
    # PICOOC supplies both muscle fields as percentages. Keep the existing
    # SkeletalMuscleMass identity so the backfill corrects rows in place.
    "SkeletalMuscleMass": ("Skeletal muscle ratio", "%"),
    "muscle_race": ("Muscle mass ratio", "%"),
}


# Preserve device diagnostics without interpreting them as chart measurements.
# PICOOC's international app can store an intermediate fat estimate under
# "subcutaneous_fat"; it does not establish an independent fat percentage.
SOURCE_DETAILS = {
    "Weight": ("electric_resistance", "correction_value_r"),
    "BodyFat": ("subcutaneous_fat",),
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
            note = "\n".join(
                f"PICOOC {field}: {sample[field]}"
                for field in SOURCE_DETAILS.get(key, ())
                if field in sample
            )
            yield record(
                when,
                "Measurement",
                name,
                value,
                unit,
                note=note,
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
