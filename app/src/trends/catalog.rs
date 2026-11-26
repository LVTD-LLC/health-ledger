//! Chart aliases, based on observed importer fields. Raw journal records keep their original spelling and units.

/// A divisor of 60,000 converts Samsung's milliseconds to the chart's minutes.
pub(super) fn measurement<'a>(source: &str, title: &'a str, unit: &'a str) -> Option<(&'a str, &'a str, i32)> {
    // RENPHO omits the standard BMI unit from its scale export.
    if source == "RENPHO" && title.eq_ignore_ascii_case("BMI") && unit.is_empty() {
        return Some(("BMI", "kg/m²", 1));
    }
    if source == "Samsung Health" && unit.is_empty() {
        let raw = match title {
            "step_count" => ("Steps", "steps", 1),
            "run_step" | "run_step_count" => ("Running steps", "steps", 1),
            "walk_step" | "walk_step_count" => ("Walking steps", "steps", 1),
            "weight" => ("Weight", "kg", 1),
            "body_fat" => ("Body fat", "%", 1),
            "muscle_mass" => ("Muscle mass ratio", "%", 1),
            "total_body_water" => ("Total body water", "L", 1),
            "active_calorie" => ("Daily active energy", "kcal", 1),
            "rest_calorie" => ("Daily resting energy", "kcal", 1),
            "total_exercise_calories" => ("Daily exercise energy", "kcal", 1),
            "walk_time" => ("Daily walking time", "min", 60_000),
            "run_time" => ("Daily running time", "min", 60_000),
            "exercise_time" => ("Daily exercise time", "min", 60_000),
            "floor_count" => ("Daily floors climbed", "floors", 1),
            // A generic Samsung CSV import can contain unrelated tables. These
            // bare fields lack a table identity, describe settings, or encode metadata.
            "count"
            | "value"
            | "type"
            | "goal"
            | "target"
            | "score"
            | "recommendation"
            | "calorie"
            | "total_calorie"
            | "distance"
            | "duration"
            | "speed"
            | "active_time"
            | "max_heart_rate"
            | "min_heart_rate"
            | "mean_heart_rate"
            | "mean_speed"
            | "max_speed"
            | "create_sh_ver"
            | "modify_sh_ver"
            | "version"
            | "version_code"
            | "source_type"
            | "completion_status"
            | "count_type"
            | "duration_type"
            | "energy_type"
            | "exercise_type"
            | "is_empty"
            | "mission_extra_value"
            | "mission_type"
            | "mission_value"
            | "movement_type"
            | "reward_status"
            | "tracking_status"
            | "heart_rate_sample_count"
            | "exercise_calorie_target"
            | "exercise_time_target"
            | "floors_target"
            | "dynamic_active_time_target"
            | "move_hourly_target"
            | "longest_active_time"
            | "longest_idle_time"
            | "dynamic_active_time"
            | "max_cadence"
            | "mean_cadence"
            | "others_time"
            | "stand_time" => return None,
            // Keep source-specific measurements distinct when their meaning or
            // units differ from a standard series.
            "healthy_step" => ("Healthy steps", "steps", 1),
            "tef_calorie" => ("Thermic effect of food", "kcal", 1),
            "move_hourly_count" => ("Hourly movement count", "", 1),
            _ => return Some(known_measurement(title, unit)),
        };
        return Some(raw);
    }
    Some(known_measurement(title, unit))
}

fn known_measurement<'a>(title: &'a str, unit: &'a str) -> (&'a str, &'a str, i32) {
    let name = title.to_ascii_lowercase();
    let canonical = match (name.as_str(), unit) {
        ("steps" | "daily steps" | "step count" | "step_count", "steps") => "Steps",
        ("running steps" | "run_step" | "run_step_count", "steps") => "Running steps",
        ("walking steps" | "walk_step" | "walk_step_count", "steps") => "Walking steps",
        ("weight" | "body weight", "kg") => "Weight",
        ("body fat" | "body_fat", "%") => "Body fat",
        ("muscle mass" | "muscle mass ratio", "%") => "Muscle mass ratio",
        ("subcutaneous fat", "%") => "Subcutaneous fat",
        ("visceral fat", "level") => "Visceral fat",
        ("body water", "%") => "Body water",
        ("total body water", "L") => "Total body water",
        ("lean body mass", "kg") => "Lean body mass",
        ("fat free weight", "kg") => "Fat-free weight",
        ("bone mass", "%") => "Bone mass ratio",
        ("body age" | "metabolic age", "years") => "Body age",
        ("heart rate", "bpm") => "Heart rate",
        ("minimum heart rate", "bpm") => "Minimum heart rate",
        ("maximum heart rate", "bpm") => "Maximum heart rate",
        ("distance", "m") => "Distance",
        ("daily activity distance", "m") => "Daily activity distance",
        ("workout distance", "m") => "Workout distance",
        ("daily active time", "min") => "Daily active time",
        ("daily walking time", "min") => "Daily walking time",
        ("daily running time", "min") => "Daily running time",
        ("daily exercise time", "min") => "Daily exercise time",
        ("daily active energy", "kcal") => "Daily active energy",
        ("daily resting energy", "kcal") => "Daily resting energy",
        ("daily exercise energy", "kcal") => "Daily exercise energy",
        ("workout energy", "kcal") => "Workout energy",
        ("workout mean speed", "m/s") => "Workout mean speed",
        ("workout maximum speed", "m/s") => "Workout maximum speed",
        ("floors climbed", "floors") => "Floors climbed",
        ("daily floors climbed", "floors") => "Daily floors climbed",
        ("height", "cm") => "Height",
        ("sleep session", "min") => "Sleep session",
        ("bmi", "" | "kg/m²") => "BMI",
        ("bmr" | "basal metabolic rate", "kcal/day") => "BMR",
        ("protein", "%") => "Protein",
        // Unknown user-defined metrics retain their title and unit. In particular,
        // weight without units outside a known source is not assumed to be kg.
        _ => title,
    };
    (canonical, unit, 1)
}
