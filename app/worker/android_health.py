"""Normalize the JSON POST format emitted by Health Connect Webhook on Android."""

from records import record, timestamp

# group -> primary field and its unit. Extra fields use the sender's explicit units.
MEASURES = {
    "steps": ("count", "steps"),
    "distance": ("meters", "m"),
    "heart_rate": ("bpm", "bpm"),
    "resting_heart_rate": ("bpm", "bpm"),
    "heart_rate_variability": ("rmssd_millis", "ms"),
    "active_calories": ("calories", "kcal"),
    "total_calories": ("calories", "kcal"),
    "weight": ("kilograms", "kg"),
    "height": ("meters", "m"),
    "bmi": ("value", "kg/m²"),
    "blood_glucose": ("mmol_per_liter", "mmol/L"),
    "blood_pressure": ("systolic", "mmHg"),
    "oxygen_saturation": ("percentage", "%"),
    "body_temperature": ("celsius", "°C"),
    "skin_temperature": ("delta_celsius", "°C"),
    "respiratory_rate": ("rate", "breaths/min"),
    "exercise": ("duration_seconds", "s"),
    "sleep": ("duration_seconds", "s"),
    "hydration": ("liters", "L"),
    "nutrition": ("calories", "kcal"),
    "basal_metabolic_rate": ("watts", "W"),
    "body_fat": ("percentage", "%"),
    "lean_body_mass": ("kilograms", "kg"),
    "body_water_mass": ("kilograms", "kg"),
    "bone_mass": ("kilograms", "kg"),
    "vo2_max": ("ml_per_kg_per_min", "mL/kg/min"),
    "menstruation_flow": ("flow", ""),
    "menstruation_period": ("", ""),
    "intermenstrual_bleeding": ("", ""),
    "ovulation_test": ("result", ""),
    "cervical_mucus": ("appearance", ""),
    "sexual_activity": ("protection_used", ""),
    "basal_body_temperature": ("celsius", "°C"),
    "mindfulness": ("duration_seconds", "s"),
}
EXTRA_UNITS = {
    "diastolic": "mmHg",
    "baseline_celsius": "°C",
    "min_delta_celsius": "°C",
    "max_delta_celsius": "°C",
    "distance_meters": "m",
    "steps": "steps",
    "avg_cadence_spm": "steps/min",
    "max_cadence_spm": "steps/min",
    "stride_length_m": "m",
    "energy_from_fat_calories": "kcal",
}
SERIES = {"heart_rate", "skin_temperature"}


def rows(data):
    if data.get("test") is True:
        return
    for group, (primary, unit) in MEASURES.items():
        for item in data.get(group, []):
            when = (
                item.get("time") or item.get("start_time") or item["session_end_time"]
            )
            instant = timestamp(when).timestamp()
            meta = item.get("metadata") or {}
            origin = meta.get("data_origin")
            source = "Android Health Connect" + (f" · {origin}" if origin else "")
            rid = meta.get("id") or meta.get("client_record_id")
            identity = [group, rid, instant if not rid or group in SERIES else None]
            group_name = group.replace("_", " ").capitalize()
            kind = {
                "sleep": "Sleep",
                "exercise": "Workout",
                "nutrition": "Nutrition",
                "mindfulness": "Check-in",
            }.get(group, "Measurement")
            name = (
                item.get("title")
                or (item.get("type") if group == "exercise" else None)
                or group_name
            )
            note = " · ".join(
                str(item[k])
                for k in ("name", "meal_type", "session_type", "notes", "end_time")
                if item.get(k)
            )
            if group == "blood_pressure":
                name = "Systolic blood pressure"
            elif group == "skin_temperature":
                name = "Skin temperature change"
            if not primary or item.get(primary) is not None:
                label = f"{name} (average)" if "avg" in item else name
                yield record(
                    when,
                    kind,
                    label,
                    item.get(primary, ""),
                    unit,
                    note,
                    source,
                    [identity, primary],
                )
            for field, value in item.items():
                if field == primary or value is None:
                    continue
                if field in ("min", "max"):
                    extra_unit = unit
                elif group == "nutrition" and field.endswith("_grams"):
                    extra_unit = "g"
                elif field in EXTRA_UNITS:
                    extra_unit = EXTRA_UNITS[field]
                else:
                    continue
                label = field.removesuffix("_grams").replace("_", " ")
                title = (
                    "Diastolic blood pressure"
                    if field == "diastolic"
                    else f"{group_name} · {label}"
                )
                yield record(
                    when,
                    kind,
                    title,
                    value,
                    extra_unit,
                    note,
                    source,
                    [identity, field],
                )
            for stage in item.get("stages", []):
                start = stage["start_time"]
                yield record(
                    start,
                    "Sleep",
                    "Sleep stage · " + str(stage["stage"]),
                    stage["duration_seconds"],
                    "s",
                    "",
                    source,
                    [identity, "stage", timestamp(start).timestamp()],
                )
