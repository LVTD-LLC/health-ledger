+++
title = "How measurements are grouped"
slug = "measurement-names"
description = "How charts combine measurements from health apps, scales and file imports."
section = "features"
order = 20
+++

## Chart names

The web dashboard and Android Trends share a measurement list. For example, RENPHO's **Body Fat**, Samsung's **body_fat** export field and a **Body fat** reading in percent appear under **Body fat (%)**. In Journal, each entry keeps its original name, value, unit and source.

| In the chart | Readings included |
| --- | --- |
| Steps | Step counts shared through Health Connect, imported daily step totals, and Samsung’s step_count field |
| Running steps | Samsung’s run_step and run_step_count |
| Walking steps | Samsung’s walk_step and walk_step_count |
| Weight (kg) | Weight measurements in kilograms, including Samsung's weight export field |
| Body fat (%) | Body-fat percentages from scales, apps and Samsung exports |
| Muscle mass ratio (%) | Muscle mass reported as a percentage of body weight |
| Total body water (L) | Body water reported in liters. Body water reported as a percentage has its own chart |
| Daily walking, running and exercise time (min) | Daily duration readings in minutes and the corresponding Samsung export fields in milliseconds |
| Daily active, resting and exercise energy (kcal) | Each energy measure shares a chart with its corresponding Samsung export field |
| Daily floors climbed | Daily floor totals. Individual floor-climbing readings have a separate chart |

## Measurement catalogue

The picker shows the measurements present in your journal. These names cover the supported exports and sync data reviewed so far.

| Family | Chart names |
| --- | --- |
| Steps and floors | Steps, Running steps, Walking steps, Healthy steps, Floors climbed, Daily floors climbed |
| Distance and speed | Distance, Daily activity distance, Workout distance, Workout mean speed, Workout maximum speed |
| Activity and sleep | Daily active time, Daily walking time, Daily running time, Daily exercise time, Hourly movement count, Sleep session |
| Energy | Daily active energy, Daily resting energy, Daily exercise energy, Workout energy, Thermic effect of food, BMR |
| Heart rate | Heart rate, Minimum heart rate, Maximum heart rate |
| Size and body composition | Weight, Height, BMI, Body age, Body fat, Subcutaneous fat, Visceral fat, Fat-free weight, Lean body mass, Bone mass ratio, Muscle mass ratio, Body water, Total body water, Protein |

## Reading the charts

Each plotted reading retains the meaning it had at the source. A Steps reading can cover a short interval or a full day. Averages, ranges and reading counts describe the readings in the chart. Two devices covering the same walk can both contribute readings. The dashboard does not add their counts into a daily activity total.

Source-specific rules supply units when a known export omits them. Unitless weight from an unfamiliar source stays separate. Unknown measurements keep their original labels. Samsung export settings, version numbers and ambiguous bare columns such as count stay accessible in the raw journal.

## Extending the catalogue

The implementation catalogue is in `app/src/trends/catalog.rs`. When adding an alias, confirm the source, unit, time scale and meaning. For example, body-fat percentage, subcutaneous fat and visceral-fat level are separate measurements.
