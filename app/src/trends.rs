//! Plot numeric measurements over time and build cross-metric dashboard summaries.
use crate::{AppError, SharedState};
use app_auth::CurrentUser;
use axum::extract::{Query, State};
use axum::http::header;
use axum::response::{IntoResponse, Response};
use maud::{Markup, html};
use serde::Serialize;
use sqlx::PgPool;
use std::collections::HashSet;
use time::{Date, Duration, Month, OffsetDateTime};
use uuid::Uuid;

const MAX_SUMMARY_CARDS: usize = 12;

/// Timeframe selector for the primary measurement chart.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Range {
    Days30,
    Days90,
    Year1,
    #[default]
    All,
}

impl Range {
    pub fn parse(raw: &str) -> Self {
        match raw.trim().to_ascii_lowercase().as_str() {
            "30d" => Self::Days30,
            "90d" => Self::Days90,
            "1y" => Self::Year1,
            _ => Self::All,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Days30 => "30d",
            Self::Days90 => "90d",
            Self::Year1 => "1y",
            Self::All => "all",
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::Days30 => "30D",
            Self::Days90 => "90D",
            Self::Year1 => "1Y",
            Self::All => "All",
        }
    }

    fn cutoff(self, anchor: OffsetDateTime) -> Option<OffsetDateTime> {
        match self {
            Self::Days30 => Some(anchor - Duration::days(30)),
            Self::Days90 => Some(anchor - Duration::days(90)),
            Self::Year1 => Some(anchor - Duration::days(365)),
            Self::All => None,
        }
    }

    const ALL: [Self; 4] = [Self::Days30, Self::Days90, Self::Year1, Self::All];
}

#[derive(Clone, Serialize)]
struct MetricOption {
    id: Uuid,
    title: String,
    unit: String,
}

struct MetricSeries {
    option: MetricOption,
    points: Vec<(OffsetDateTime, f64)>,
}

/// Pre-rendered chart and cross-metric summary data for the dashboard overview.
pub struct Overview {
    pub total_metrics: usize,
    pub latest_date: Option<Date>,
    pub chart: Markup,
    pub metrics_grid: Markup,
}

async fn metric_options(pool: &PgPool, owner: Uuid) -> Result<Vec<MetricOption>, sqlx::Error> {
    let raw_options = sqlx::query!(
        "SELECT DISTINCT ON(title,unit) id,title,unit FROM health_records WHERE user_id=$1 AND kind IN ('Measurement','Lab','Sleep','Nutrition') AND value ~ '^[+-]?[0-9]+([.][0-9]+)?$' ORDER BY title,unit,occurred_at DESC,id DESC LIMIT 250",
        owner
    )
    .fetch_all(pool)
    .await?;

    Ok(raw_options.into_iter().map(|r| MetricOption { id: r.id, title: r.title, unit: r.unit }).collect())
}

fn choose_metric(options: &[MetricOption], selected: Option<Uuid>) -> Option<&MetricOption> {
    options
        .iter()
        .find(|o| Some(o.id) == selected)
        .or_else(|| options.iter().min_by_key(|o| (metric_priority(&o.title), o.title.to_ascii_lowercase())))
}

/// Aggregate the selected metric in UTC calendar buckets before transferring it to the phone.
pub async fn aggregates(
    State(state): State<SharedState>,
    CurrentUser(user): CurrentUser,
    Query(filter): Query<crate::health::Filter>,
) -> Result<Response, AppError> {
    let options = metric_options(&state.pool, user.id).await?;
    let chosen = choose_metric(&options, filter.metric);
    let today = OffsetDateTime::now_utc().date();
    let days = match filter.range.as_str() {
        "7d" => Some(7),
        "90d" => Some(90),
        "all" => None,
        _ => Some(30),
    };
    let mut buckets = Vec::new();
    if let Some(metric) = chosen {
        let start = days.map(|n| (today - Duration::days(n - 1)).midnight().assume_utc());
        let until = (today + Duration::days(1)).midnight().assume_utc();
        let period = if days.is_some() { "day" } else { "month" };
        let rows = sqlx::query!(
            r#"SELECT date_trunc($4, occurred_at AT TIME ZONE 'UTC')::date AS "day!",
                      count(*) AS "count!", avg(value::numeric)::text AS "mean!",
                      min(value::numeric)::text AS "low!", max(value::numeric)::text AS "high!"
               FROM health_records
               WHERE user_id=$1 AND title=$2 AND unit=$3
                 AND kind IN ('Measurement','Lab','Sleep','Nutrition')
                 AND value ~ '^[+-]?[0-9]+([.][0-9]+)?$'
                 AND ($5::timestamptz IS NULL OR occurred_at >= $5) AND occurred_at < $6
               GROUP BY 1 ORDER BY 1"#,
            user.id,
            metric.title,
            metric.unit,
            period,
            start,
            until,
        )
        .fetch_all(&state.pool)
        .await?;
        buckets.extend(rows.into_iter().map(|r| {
            serde_json::json!({
                "date": r.day.to_string(), "count": r.count, "mean": r.mean, "low": r.low, "high": r.high
            })
        }));
    }
    let data = serde_json::json!({"metrics": options, "selected": chosen.map(|m| m.id), "buckets": buckets, "today": today.to_string()});
    Ok(([(header::CACHE_CONTROL, "private, no-store")], axum::Json(data)).into_response())
}

/// Fetch numeric measurements and render both the interactive trend chart and summary cards.
pub async fn overview(
    pool: &PgPool,
    owner: Uuid,
    selected: Option<Uuid>,
    range: Range,
) -> Result<Overview, sqlx::Error> {
    let options = metric_options(pool, owner).await?;

    let Some(chosen) = choose_metric(&options, selected).cloned() else {
        return Ok(Overview { total_metrics: 0, latest_date: None, chart: html! {}, metrics_grid: html! {} });
    };

    // Pick up to MAX_SUMMARY_CARDS metrics, always including the active metric first in priority order.
    let mut prioritized = options.clone();
    prioritized.sort_by(|a, b| {
        metric_priority(&a.title)
            .cmp(&metric_priority(&b.title))
            .then_with(|| a.title.to_ascii_lowercase().cmp(&b.title.to_ascii_lowercase()))
            .then_with(|| a.unit.cmp(&b.unit))
    });
    let mut featured: Vec<MetricOption> = Vec::with_capacity(MAX_SUMMARY_CARDS);
    let mut seen: HashSet<Uuid> = HashSet::new();
    for item in prioritized.into_iter().take(MAX_SUMMARY_CARDS) {
        if seen.insert(item.id) {
            featured.push(item);
        }
    }
    if seen.insert(chosen.id) {
        if featured.len() >= MAX_SUMMARY_CARDS {
            featured.pop();
        }
        featured.insert(0, chosen.clone());
    }

    let mut series_list: Vec<MetricSeries> = Vec::with_capacity(featured.len());
    for opt in featured {
        let points = fetch_points(pool, owner, &opt.title, &opt.unit).await?;
        if !points.is_empty() {
            series_list.push(MetricSeries { option: opt, points });
        }
    }

    let latest_date = series_list.iter().filter_map(|s| s.points.last().map(|(t, _)| t.date())).max();

    let selected_series = series_list.iter().find(|s| s.option.id == chosen.id).or_else(|| series_list.first());

    let chart = match selected_series {
        Some(active) => render_trend_card(&options, active, range),
        None => html! {},
    };

    let metrics_grid = render_metrics_grid(&series_list, chosen.id, options.len(), range);

    Ok(Overview { total_metrics: options.len(), latest_date, chart, metrics_grid })
}

async fn fetch_points(
    pool: &PgPool,
    owner: Uuid,
    title: &str,
    unit: &str,
) -> Result<Vec<(OffsetDateTime, f64)>, sqlx::Error> {
    let rows = sqlx::query!(
        "SELECT occurred_at,value FROM health_records WHERE user_id=$1 AND title=$2 AND unit=$3 AND kind IN ('Measurement','Lab','Sleep','Nutrition') AND value ~ '^[+-]?[0-9]+([.][0-9]+)?$' ORDER BY occurred_at DESC,id DESC LIMIT 90",
        owner,
        title,
        unit
    )
    .fetch_all(pool)
    .await?;

    Ok(rows
        .iter()
        .rev()
        .filter_map(|r| Some((r.occurred_at, r.value.parse::<f64>().ok()?)))
        .filter(|(_, n)| n.is_finite())
        .collect())
}

/// Lower score = surfaced higher in the summary grid.
fn metric_priority(title: &str) -> u8 {
    let t = title.to_ascii_lowercase();
    if t == "weight" || t.starts_with("weight ") || t == "body weight" {
        return 0;
    }
    if t.contains("body fat") || t == "fat" {
        return 1;
    }
    if t == "bmi" {
        return 2;
    }
    if t.contains("muscle") {
        return 3;
    }
    if t.contains("resting") && (t.contains("hr") || t.contains("heart")) {
        return 4;
    }
    if t == "hrv" || t.contains("heart rate variability") {
        return 5;
    }
    if t.contains("sleep") {
        return 6;
    }
    if t.contains("step") {
        return 7;
    }
    if t.contains("systolic") || t.contains("diastolic") || t.contains("blood pressure") {
        return 8;
    }
    if t.contains("spo2") || t.contains("oxygen") {
        return 9;
    }
    if t.contains("water") || t.contains("hydration") {
        return 10;
    }
    if t.contains("visceral") || t.contains("bmr") || t.contains("metabolic") || t.contains("bone") {
        return 11;
    }
    if t.contains("calorie") || t.contains("protein") || t.contains("waist") || t.contains("glucose") {
        return 12;
    }
    20
}

fn render_trend_card(options: &[MetricOption], series: &MetricSeries, range: Range) -> Markup {
    let metric = &series.option;
    let all_points = &series.points;
    let now = OffsetDateTime::now_utc();
    let filtered: Vec<(OffsetDateTime, f64)> = match range.cutoff(now) {
        Some(cutoff) => all_points.iter().copied().filter(|(t, _)| *t >= cutoff).collect(),
        None => all_points.clone(),
    };

    let display_points = if filtered.is_empty() { &[] as &[(OffsetDateTime, f64)] } else { filtered.as_slice() };
    let unit_suffix = if metric.unit.is_empty() { String::new() } else { format!(" {}", metric.unit) };

    let stats = compute_stats(display_points);
    let chart_svg = render_chart_svg(metric, display_points, &stats);

    html! {
        section id="trend" class="card trend-card" aria-labelledby="trend-heading" {
            div class="trend-head" {
                div class="trend-title-group" {
                    span class="eyebrow-label" { "Measurement trend" }
                    h2 id="trend-heading" class="trend-title" {
                        (metric.title)
                        @if !metric.unit.is_empty() {
                            span class="unit-pill" { (metric.unit) }
                        }
                    }
                }
                div class="trend-controls" {
                    form method="get" action="/dashboard#trend" class="metric-picker" data-auto-submit {
                        label class="visually-hidden" for="metric-select" { "Select measurement" }
                        select id="metric-select" name="metric" {
                            @for o in options {
                                option value=(o.id) selected[o.id == metric.id] {
                                    (o.title)
                                    @if !o.unit.is_empty() { " (" (o.unit) ")" }
                                }
                            }
                        }
                        @if range != Range::All {
                            input type="hidden" name="range" value=(range.as_str());
                        }
                        button type="submit" class="btn metric-picker-submit" { "Show trend" }
                    }
                    div class="range-pills" role="group" aria-label="Time range" {
                        @for r in Range::ALL {
                            a class="range-pill"
                                href={ "/dashboard?metric=" (metric.id) "&range=" (r.as_str()) "#trend" }
                                aria-current=[(r == range).then_some("page")] {
                                (r.label())
                            }
                        }
                    }
                }
            }

            @if let Some(st) = &stats {
                div class="trend-kpis" {
                    div class="trend-kpi trend-kpi-primary" {
                        span class="trend-kpi-label" { "Latest" }
                        strong class="trend-kpi-value" {
                            (fmt_num(st.latest_val))
                            @if !metric.unit.is_empty() { span class="trend-kpi-unit" { " " (metric.unit) } }
                        }
                        span class="trend-kpi-sub" { (fmt_full_date(st.latest_at.date())) }
                    }
                    div class="trend-kpi" {
                        span class="trend-kpi-label" { "Change" }
                        @if let Some((delta, pct)) = st.change {
                            strong class=(delta_class(delta)) {
                                (fmt_signed(delta))
                                @if !metric.unit.is_empty() { span class="trend-kpi-unit" { " " (metric.unit) } }
                            }
                            span class="trend-kpi-sub" {
                                @if let Some(p) = pct {
                                    (fmt_signed_pct(p)) " over period"
                                } @else {
                                    "Over displayed period"
                                }
                            }
                        } @else {
                            strong class="trend-kpi-value muted" { "—" }
                            span class="trend-kpi-sub" { "Single reading" }
                        }
                    }
                    div class="trend-kpi" {
                        span class="trend-kpi-label" { "Average" }
                        strong class="trend-kpi-value" {
                            (fmt_num(st.avg))
                            @if !metric.unit.is_empty() { span class="trend-kpi-unit" { " " (metric.unit) } }
                        }
                        span class="trend-kpi-sub" { (st.count) " " (if st.count == 1 { "reading" } else { "readings" }) }
                    }
                    div class="trend-kpi" {
                        span class="trend-kpi-label" { "Range" }
                        strong class="trend-kpi-value" {
                            (fmt_num(st.min)) " – " (fmt_num(st.max))
                            @if !metric.unit.is_empty() { span class="trend-kpi-unit" { " " (metric.unit) } }
                        }
                        span class="trend-kpi-sub" { (fmt_short_date(st.first_at.date(), st.multi_year)) " – " (fmt_short_date(st.latest_at.date(), st.multi_year)) }
                    }
                }
                (chart_svg)
                div class="trend-foot" {
                    span class="muted small" {
                        (st.count) " " (if st.count == 1 { "reading" } else { "readings" })
                        " · "
                        (st.first_at.date()) " – " (st.latest_at.date())
                        " · range " (fmt_num(st.min)) "–" (fmt_num(st.max)) (unit_suffix)
                    }
                    @if st.count > 1 {
                        span class="trend-legend small muted" {
                            span class="legend-swatch legend-line" aria-hidden="true" {} " Reading"
                            @if st.count > 2 {
                                span class="legend-swatch legend-avg" aria-hidden="true" {} " Average"
                            }
                        }
                    }
                }
            } @else {
                div class="chart-empty" {
                    p { "No " (metric.title) " readings recorded in the last " (range.label()) "." }
                    a class="btn" href={ "/dashboard?metric=" (metric.id) "&range=all#trend" } { "Show all readings" }
                }
            }
        }
    }
}

struct Stats {
    count: usize,
    first_at: OffsetDateTime,
    latest_at: OffsetDateTime,
    latest_val: f64,
    min: f64,
    max: f64,
    avg: f64,
    change: Option<(f64, Option<f64>)>,
    multi_year: bool,
}

fn compute_stats(points: &[(OffsetDateTime, f64)]) -> Option<Stats> {
    let (first, last) = (points.first()?, points.last()?);
    let mut min = f64::INFINITY;
    let mut max = f64::NEG_INFINITY;
    let mut sum = 0.0;
    for &(_, v) in points {
        if v < min {
            min = v;
        }
        if v > max {
            max = v;
        }
        sum += v;
    }
    let avg = sum / points.len() as f64;
    let change = if points.len() > 1 {
        let delta = last.1 - first.1;
        let pct = if first.1.abs() > 1e-9 { Some((delta / first.1.abs()) * 100.0) } else { None };
        Some((delta, pct))
    } else {
        None
    };
    Some(Stats {
        count: points.len(),
        first_at: first.0,
        latest_at: last.0,
        latest_val: last.1,
        min,
        max,
        avg,
        change,
        multi_year: first.0.year() != last.0.year(),
    })
}

fn render_chart_svg(metric: &MetricOption, points: &[(OffsetDateTime, f64)], stats: &Option<Stats>) -> Markup {
    let Some(st) = stats else { return html! {} };

    let w = 760.0_f64;
    let h = 276.0_f64;
    let pad_l = 60.0_f64;
    let pad_r = 22.0_f64;
    let pad_t = 18.0_f64;
    let pad_b = 34.0_f64;
    let plot_w = w - pad_l - pad_r;
    let plot_h = h - pad_t - pad_b;
    let base_y = pad_t + plot_h;

    let scale = nice_scale(st.min, st.max, 5);
    let y_span = (scale.max - scale.min).max(1e-9);
    let map_y = |v: f64| -> f64 { pad_t + plot_h * (1.0 - (v - scale.min) / y_span) };

    let first_t = points[0].0;
    let last_t = points[points.len() - 1].0;
    let t_span = (last_t - first_t).as_seconds_f64();
    let map_x = |t: OffsetDateTime, idx: usize| -> f64 {
        if points.len() == 1 {
            pad_l + plot_w * 0.5
        } else if t_span > 0.0 {
            pad_l + plot_w * ((t - first_t).as_seconds_f64() / t_span)
        } else {
            pad_l + plot_w * (idx as f64 / (points.len() - 1) as f64)
        }
    };

    let coords: Vec<(f64, f64, OffsetDateTime, f64, Option<f64>)> = points
        .iter()
        .enumerate()
        .map(|(i, &(t, v))| {
            let prev = if i > 0 { Some(v - points[i - 1].1) } else { None };
            (map_x(t, i), map_y(v), t, v, prev)
        })
        .collect();

    let mut line_path = String::new();
    let mut area_path = String::new();
    if let Some(first) = coords.first() {
        for (i, &(x, y, _, _, _)) in coords.iter().enumerate() {
            if i == 0 {
                line_path.push_str(&format!("M{x:.1},{y:.1}"));
                area_path.push_str(&format!("M{x:.1},{base_y:.1} L{x:.1},{y:.1}"));
            } else {
                line_path.push_str(&format!(" L{x:.1},{y:.1}"));
                area_path.push_str(&format!(" L{x:.1},{y:.1}"));
            }
        }
        if let Some(last) = coords.last() {
            area_path.push_str(&format!(" L{:.1},{base_y:.1} Z", last.0));
        }
        let _ = first;
    }

    let x_ticks = build_x_ticks(first_t, last_t, pad_l, plot_w, st.multi_year, points.len());
    let avg_y = map_y(st.avg);
    let show_dots = coords.len() <= 45;
    let latest = coords.last().copied();

    let aria_label = format!(
        "{} from {} to {}, latest {} {}, range {} to {} {}",
        metric.title,
        st.first_at.date(),
        st.latest_at.date(),
        fmt_num(st.latest_val),
        metric.unit,
        fmt_num(st.min),
        fmt_num(st.max),
        metric.unit
    );

    html! {
        div class="chart-stage" data-chart-stage {
            svg class="trend-chart-svg"
                role="img"
                aria-label=(aria_label)
                viewBox={ "0 0 " (w as i32) " " (h as i32) }
                preserveAspectRatio="xMidYMid meet" {
                defs {
                    linearGradient id="trend-area-grad" x1="0" y1="0" x2="0" y2="1" {
                        stop offset="0%" stop-color="currentColor" stop-opacity="0.22" {}
                        stop offset="100%" stop-color="currentColor" stop-opacity="0.0" {}
                    }
                }

                // Horizontal gridlines & Y-axis labels
                @for tick in &scale.ticks {
                    @let ty = map_y(*tick);
                    line class="chart-grid" x1=(format!("{pad_l:.1}")) y1=(format!("{ty:.1}")) x2=(format!("{:.1}", w - pad_r)) y2=(format!("{ty:.1}")) {}
                    text class="chart-axis-label chart-y-label" x=(format!("{:.1}", pad_l - 10.0)) y=(format!("{:.1}", ty + 4.0)) text-anchor="end" {
                        (fmt_num(*tick))
                    }
                }

                // X-axis baseline
                line class="chart-baseline" x1=(format!("{pad_l:.1}")) y1=(format!("{base_y:.1}")) x2=(format!("{:.1}", w - pad_r)) y2=(format!("{base_y:.1}")) {}

                // X-axis date ticks
                @for (tx, label, anchor) in &x_ticks {
                    text class="chart-axis-label chart-x-label" x=(format!("{tx:.1}")) y=(format!("{:.1}", h - 10.0)) text-anchor=(anchor) {
                        (label)
                    }
                }

                // Average reference line
                @if coords.len() > 2 {
                    line class="chart-avg-line" x1=(format!("{pad_l:.1}")) y1=(format!("{avg_y:.1}")) x2=(format!("{:.1}", w - pad_r)) y2=(format!("{avg_y:.1}")) {}
                    text class="chart-avg-tag" x=(format!("{:.1}", w - pad_r)) y=(format!("{:.1}", (avg_y - 5.0).max(pad_t + 9.0))) text-anchor="end" {
                        "Avg " (fmt_num(st.avg))
                    }
                }

                // Area gradient & data stroke
                @if coords.len() > 1 {
                    path class="chart-area" d=(area_path) fill="url(#trend-area-grad)" {}
                    path class="chart-line" d=(line_path) fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" {}
                }

                // Interactive crosshair (shown on pointer hover by health.js)
                line class="chart-crosshair" data-chart-crosshair x1="0" y1=(format!("{pad_t:.1}")) x2="0" y2=(format!("{base_y:.1}")) hidden {}

                // Data points
                @for (i, &(x, y, t, v, prev)) in coords.iter().enumerate() {
                    @let is_last = i + 1 == coords.len();
                    @let delta_str = prev.map(fmt_signed).unwrap_or_default();
                    @let formatted_val = fmt_num(v);
                    @let formatted_date = fmt_full_date(t.date());
                    @if show_dots || is_last {
                        circle class=(if is_last { "chart-point chart-point-latest" } else { "chart-point" })
                            cx=(format!("{x:.1}"))
                            cy=(format!("{y:.1}"))
                            r=(if is_last { "4.5" } else { "3" })
                            data-chart-point
                            data-x=(format!("{x:.1}"))
                            data-y=(format!("{y:.1}"))
                            data-val=(formatted_val)
                            data-unit=(metric.unit)
                            data-date=(formatted_date)
                            data-delta=(delta_str) {
                            title {
                                (formatted_val)
                                @if !metric.unit.is_empty() { " " (metric.unit) }
                                " on " (formatted_date)
                            }
                        }
                    } @else {
                        circle class="chart-point chart-point-hidden"
                            cx=(format!("{x:.1}"))
                            cy=(format!("{y:.1}"))
                            r="3"
                            data-chart-point
                            data-x=(format!("{x:.1}"))
                            data-y=(format!("{y:.1}"))
                            data-val=(formatted_val)
                            data-unit=(metric.unit)
                            data-date=(formatted_date)
                            data-delta=(delta_str) {}
                    }
                }

                // Latest value soft halo
                @if let Some((lx, ly, _, _, _)) = latest {
                    circle class="chart-point-halo" cx=(format!("{lx:.1}")) cy=(format!("{ly:.1}")) r="9" {}
                }

                // Hover focus ring (positioned by health.js)
                circle class="chart-focus-dot" data-chart-focus cx="0" cy="0" r="5.5" hidden {}
            }
            div class="chart-tooltip" data-chart-tooltip hidden role="status" aria-live="polite" {}
        }
    }
}

fn render_metrics_grid(series_list: &[MetricSeries], selected_id: Uuid, total_metrics: usize, range: Range) -> Markup {
    if series_list.is_empty() {
        return html! {};
    }
    html! {
        div class="metrics-section" {
            div class="section-bar" {
                div {
                    h2 class="section-heading" { "Latest across your metrics" }
                    @if total_metrics > series_list.len() {
                        p class="muted small" {
                            "Showing " (series_list.len()) " of " (total_metrics) " tracked metrics. Select any metric above to inspect its full history."
                        }
                    } @else {
                        p class="muted small" { "Click any metric to inspect its trend chart." }
                    }
                }
            }
            div class="metric-grid" {
                @for (idx, s) in series_list.iter().enumerate() {
                    @let active = s.option.id == selected_id;
                    @let last = s.points.last().expect("non-empty series");
                    @let delta = if s.points.len() > 1 { Some(last.1 - s.points[s.points.len() - 2].1) } else { None };
                    @let href = if range == Range::All {
                        format!("/dashboard?metric={}#trend", s.option.id)
                    } else {
                        format!("/dashboard?metric={}&range={}#trend", s.option.id, range.as_str())
                    };
                    a class=(if active { "metric-card is-active" } else { "metric-card" })
                        href=(href)
                        aria-current=[active.then_some("true")] {
                        div class="metric-card-top" {
                            span class="metric-card-name" { (s.option.title) }
                            @if let Some(d) = delta {
                                span class=(delta_pill_class(d)) { (fmt_signed(d)) }
                            }
                        }
                        div class="metric-card-mid" {
                            strong class="metric-card-val" {
                                (fmt_num(last.1))
                                @if !s.option.unit.is_empty() {
                                    span class="metric-card-unit" { " " (s.option.unit) }
                                }
                            }
                            (render_sparkline(&s.points, idx))
                        }
                        div class="metric-card-foot" {
                            span class="muted small" { (fmt_full_date(last.0.date())) }
                            span class="muted small" { (s.points.len()) " " (if s.points.len() == 1 { "pt" } else { "pts" }) }
                        }
                    }
                }
            }
        }
    }
}

fn render_sparkline(points: &[(OffsetDateTime, f64)], idx: usize) -> Markup {
    if points.len() < 2 {
        return html! { span class="spark-empty" aria-hidden="true" {} };
    }
    let slice = if points.len() > 24 { &points[points.len() - 24..] } else { points };
    let min = slice.iter().map(|p| p.1).fold(f64::INFINITY, f64::min);
    let max = slice.iter().map(|p| p.1).fold(f64::NEG_INFINITY, f64::max);
    let w = 110.0_f64;
    let h = 32.0_f64;
    let pad = 3.0_f64;
    let span = max - min;
    let last_idx = (slice.len() - 1) as f64;
    let mut line = String::new();
    let mut area = String::new();
    let mut end = (w - pad, h * 0.5);
    for (i, &(_, v)) in slice.iter().enumerate() {
        let x = pad + (w - 2.0 * pad) * (i as f64 / last_idx);
        let y = if span > 1e-9 { pad + (h - 2.0 * pad) * (1.0 - (v - min) / span) } else { h * 0.5 };
        if i == 0 {
            line.push_str(&format!("M{x:.1},{y:.1}"));
            area.push_str(&format!("M{x:.1},{h:.1} L{x:.1},{y:.1}"));
        } else {
            line.push_str(&format!(" L{x:.1},{y:.1}"));
            area.push_str(&format!(" L{x:.1},{y:.1}"));
        }
        end = (x, y);
    }
    area.push_str(&format!(" L{:.1},{h:.1} Z", end.0));
    let grad_id = format!("spark-grad-{idx}");

    html! {
        svg class="sparkline" viewBox="0 0 110 32" aria-hidden="true" {
            defs {
                linearGradient id=(grad_id) x1="0" y1="0" x2="0" y2="1" {
                    stop offset="0%" stop-color="currentColor" stop-opacity="0.24" {}
                    stop offset="100%" stop-color="currentColor" stop-opacity="0.0" {}
                }
            }
            path d=(area) fill={ "url(#" (grad_id) ")" } {}
            path d=(line) fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" {}
            circle cx=(format!("{:.1}", end.0)) cy=(format!("{:.1}", end.1)) r="2.4" fill="currentColor" {}
        }
    }
}

struct NiceScale {
    min: f64,
    max: f64,
    ticks: Vec<f64>,
}

/// Compute human-friendly rounded Y-axis bounds and tick marks.
fn nice_scale(raw_min: f64, raw_max: f64, target_ticks: usize) -> NiceScale {
    let target = target_ticks.max(2);
    let (lo, hi) = if (raw_max - raw_min).abs() < 1e-9 {
        let pad = (raw_min.abs() * 0.05).max(1.0);
        (raw_min - pad, raw_max + pad)
    } else {
        let span = raw_max - raw_min;
        let pad = span * 0.08;
        let lo = if raw_min >= 0.0 { (raw_min - pad).max(0.0) } else { raw_min - pad };
        (lo, raw_max + pad)
    };

    let range = nice_num(hi - lo, false);
    let step = nice_num(range / (target - 1) as f64, true);
    let min = (lo / step).floor() * step;
    let max = (hi / step).ceil() * step;

    let mut ticks = Vec::new();
    let count = ((max - min) / step).round().max(1.0) as usize;
    for i in 0..=count.min(10) {
        let v = min + step * i as f64;
        ticks.push(if v.abs() < step * 1e-6 { 0.0 } else { v });
    }
    NiceScale { min, max, ticks }
}

fn nice_num(range: f64, round: bool) -> f64 {
    let exponent = range.abs().max(1e-12).log10().floor();
    let fraction = range / 10_f64.powf(exponent);
    let nice = if round {
        if fraction < 1.5 {
            1.0
        } else if fraction < 3.0 {
            2.0
        } else if fraction < 7.0 {
            5.0
        } else {
            10.0
        }
    } else if fraction <= 1.0 {
        1.0
    } else if fraction <= 2.0 {
        2.0
    } else if fraction <= 5.0 {
        5.0
    } else {
        10.0
    };
    nice * 10_f64.powf(exponent)
}

fn build_x_ticks(
    first: OffsetDateTime,
    last: OffsetDateTime,
    pad_l: f64,
    plot_w: f64,
    multi_year: bool,
    point_count: usize,
) -> Vec<(f64, String, &'static str)> {
    if point_count <= 1 || first == last {
        return vec![(pad_l + plot_w * 0.5, fmt_short_date(first.date(), multi_year), "middle")];
    }
    let same_day = first.date() == last.date();
    let span_secs = (last - first).as_seconds_f64();
    let slots = if point_count <= 3 { point_count } else { 5 };
    let mut ticks = Vec::with_capacity(slots);
    let mut prev_label = String::new();
    for i in 0..slots {
        let frac = i as f64 / (slots - 1) as f64;
        let ts = first + Duration::seconds_f64(span_secs * frac);
        let label = if same_day {
            format!("{:02}:{:02}", ts.hour(), ts.minute())
        } else {
            fmt_short_date(ts.date(), multi_year)
        };
        if label == prev_label && i + 1 < slots {
            continue;
        }
        prev_label.clone_from(&label);
        let x = pad_l + plot_w * frac;
        let anchor = if i == 0 {
            "start"
        } else if i + 1 == slots {
            "end"
        } else {
            "middle"
        };
        ticks.push((x, label, anchor));
    }
    ticks
}

fn delta_class(delta: f64) -> &'static str {
    if delta.abs() < 1e-6 {
        "trend-kpi-value delta-flat"
    } else if delta > 0.0 {
        "trend-kpi-value delta-up"
    } else {
        "trend-kpi-value delta-down"
    }
}

fn delta_pill_class(delta: f64) -> &'static str {
    if delta.abs() < 1e-6 {
        "delta-pill delta-flat"
    } else if delta > 0.0 {
        "delta-pill delta-up"
    } else {
        "delta-pill delta-down"
    }
}

/// Clean human-readable number formatting without floating-point artifacts.
pub fn fmt_num(v: f64) -> String {
    if !v.is_finite() {
        return "—".into();
    }
    let v = if v.abs() < 1e-9 { 0.0 } else { v };
    let abs = v.abs();
    let formatted = if abs >= 100.0 {
        trim_zeros(&format!("{v:.1}"))
    } else if abs >= 1.0 || abs == 0.0 {
        trim_zeros(&format!("{v:.2}"))
    } else {
        trim_zeros(&format!("{v:.3}"))
    };
    if abs >= 10_000.0 { add_thousands_sep(&formatted) } else { formatted }
}

fn fmt_signed(v: f64) -> String {
    if v.abs() < 1e-6 {
        "0".into()
    } else if v > 0.0 {
        format!("+{}", fmt_num(v))
    } else {
        format!("−{}", fmt_num(v.abs()))
    }
}

fn fmt_signed_pct(p: f64) -> String {
    let rounded = (p * 10.0).round() / 10.0;
    if rounded.abs() < 0.05 {
        "0%".into()
    } else if rounded > 0.0 {
        format!("+{:.1}%", rounded)
    } else {
        format!("−{:.1}%", rounded.abs())
    }
}

fn trim_zeros(s: &str) -> String {
    if !s.contains('.') {
        return s.to_owned();
    }
    let trimmed = s.trim_end_matches('0').trim_end_matches('.');
    if trimmed == "-0" { "0".into() } else { trimmed.to_owned() }
}

fn add_thousands_sep(s: &str) -> String {
    let (sign, rest) = match s.strip_prefix('-') {
        Some(r) => ("-", r),
        None => ("", s),
    };
    let (int_part, frac_part) = match rest.split_once('.') {
        Some((i, f)) => (i, Some(f)),
        None => (rest, None),
    };
    let mut out = String::with_capacity(s.len() + 3);
    out.push_str(sign);
    for (idx, ch) in int_part.chars().enumerate() {
        if idx > 0 && (int_part.len() - idx).is_multiple_of(3) {
            out.push(',');
        }
        out.push(ch);
    }
    if let Some(f) = frac_part {
        out.push('.');
        out.push_str(f);
    }
    out
}

fn month_short(m: Month) -> &'static str {
    match m {
        Month::January => "Jan",
        Month::February => "Feb",
        Month::March => "Mar",
        Month::April => "Apr",
        Month::May => "May",
        Month::June => "Jun",
        Month::July => "Jul",
        Month::August => "Aug",
        Month::September => "Sep",
        Month::October => "Oct",
        Month::November => "Nov",
        Month::December => "Dec",
    }
}

fn fmt_short_date(d: Date, include_year: bool) -> String {
    if include_year {
        format!("{} {} '{:02}", d.day(), month_short(d.month()), d.year().rem_euclid(100))
    } else {
        format!("{} {}", d.day(), month_short(d.month()))
    }
}

pub fn fmt_full_date(d: Date) -> String {
    format!("{} {} {}", d.day(), month_short(d.month()), d.year())
}

#[cfg(test)]
mod tests {
    use super::*;
    use time::macros::datetime;

    #[test]
    fn nice_scale_produces_clean_bounds_for_renpho_weight_range() {
        let s = nice_scale(80.35, 85.55, 5);
        assert!(s.min <= 80.35, "min {} must cover 80.35", s.min);
        assert!(s.max >= 85.55, "max {} must cover 85.55", s.max);
        assert!(s.ticks.len() >= 4 && s.ticks.len() <= 8, "ticks: {:?}", s.ticks);
        assert!(s.ticks.windows(2).all(|w| w[1] > w[0]));
    }

    #[test]
    fn nice_scale_handles_constant_series() {
        let s = nice_scale(72.0, 72.0, 5);
        assert!(s.min < 72.0 && s.max > 72.0);
        assert!(s.ticks.len() >= 3);
    }

    #[test]
    fn formatting_trims_trailing_noise_and_formats_deltas() {
        assert_eq!(fmt_num(84.55000000000001), "84.55");
        assert_eq!(fmt_num(80.0), "80");
        assert_eq!(fmt_num(12450.0), "12,450");
        assert_eq!(fmt_signed(1.25), "+1.25");
        assert_eq!(fmt_signed(-0.4), "−0.4");
        assert_eq!(fmt_signed(0.0), "0");
    }

    #[test]
    fn chart_svg_renders_axes_grid_area_and_interactive_points() {
        let metric = MetricOption { id: Uuid::nil(), title: "Weight".into(), unit: "kg".into() };
        let points = vec![
            (datetime!(2026-05-29 08:00 UTC), 81.4),
            (datetime!(2026-08-15 08:00 UTC), 80.35),
            (datetime!(2026-10-10 08:00 UTC), 85.55),
            (datetime!(2026-11-21 08:00 UTC), 84.55),
        ];
        let stats = compute_stats(&points);
        let markup = render_chart_svg(&metric, &points, &stats).into_string();
        assert!(markup.contains("class=\"trend-chart-svg\""));
        assert!(markup.contains("class=\"chart-grid\""));
        assert!(markup.contains("class=\"chart-area\""));
        assert!(markup.contains("class=\"chart-line\""));
        assert!(markup.contains("class=\"chart-avg-line\""));
        assert!(markup.contains("data-chart-point"));
        assert!(markup.contains("data-val=\"84.55\""));
        assert!(markup.contains("data-chart-tooltip"));
    }

    #[test]
    fn single_point_chart_renders_cleanly_without_nan() {
        let metric = MetricOption { id: Uuid::nil(), title: "Weight".into(), unit: "kg".into() };
        let points = vec![(datetime!(2026-11-21 08:00 UTC), 84.55)];
        let stats = compute_stats(&points);
        let markup = render_chart_svg(&metric, &points, &stats).into_string();
        assert!(!markup.contains("NaN"));
        assert!(markup.contains("84.55"));
    }
}
