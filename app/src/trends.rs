//! Plot a single numeric measurement in its original unit over time.
use maud::{Markup, html};
use sqlx::PgPool;
use uuid::Uuid;

pub async fn panel(pool: &PgPool, owner: Uuid, selected: Option<Uuid>) -> Result<Markup, sqlx::Error> {
    let options=sqlx::query!("SELECT DISTINCT ON(title,unit) id,title,unit FROM health_records WHERE user_id=$1 AND kind IN ('Measurement','Lab','Sleep','Nutrition') AND value ~ '^[+-]?[0-9]+([.][0-9]+)?$' ORDER BY title,unit,occurred_at DESC,id DESC LIMIT 250",owner).fetch_all(pool).await?;
    let Some(metric) = options.iter().find(|r| Some(r.id) == selected).or(options.first()) else { return Ok(html! {}) };
    let rows=sqlx::query!("SELECT occurred_at,value FROM health_records WHERE user_id=$1 AND title=$2 AND unit=$3 AND kind IN ('Measurement','Lab','Sleep','Nutrition') AND value ~ '^[+-]?[0-9]+([.][0-9]+)?$' ORDER BY occurred_at DESC,id DESC LIMIT 90",owner,metric.title,metric.unit).fetch_all(pool).await?;
    let points: Vec<_> = rows
        .iter()
        .rev()
        .filter_map(|r| Some((r.occurred_at, r.value.parse::<f64>().ok()?)))
        .filter(|(_, n)| n.is_finite())
        .collect();
    let (Some(first), Some(last)) = (points.first(), points.last()) else { return Ok(html! {}) };
    let min = points.iter().map(|p| p.1).fold(f64::INFINITY, f64::min);
    let max = points.iter().map(|p| p.1).fold(f64::NEG_INFINITY, f64::max);
    let seconds = (last.0 - first.0).as_seconds_f64();
    let line = points
        .iter()
        .map(|(t, v)| {
            let x = if seconds > 0.0 { 8.0 + 584.0 * (*t - first.0).as_seconds_f64() / seconds } else { 300.0 };
            let y = if max > min { 98.0 - 90.0 * (v - min) / (max - min) } else { 53.0 };
            format!("{x:.1},{y:.1}")
        })
        .collect::<Vec<_>>()
        .join(" ");
    Ok(html! {section id="trend" {
        h2 {"Measurement history"}
        form method="get" action="/dashboard#trend" {label {"Measurement" select name="metric" {@for o in &options{option value=(o.id) selected[o.id==metric.id] {(o.title) " " (o.unit)}}}} button {"Show trend"}}
        p {strong {(last.1) " " (metric.unit)} " on " (last.0.date())}
        @if points.len()>1 {svg role="img" aria-label={ (metric.title) " from " (first.0.date()) " to " (last.0.date()) ", range " (min) " to " (max) " " (metric.unit) } viewBox="0 0 600 106" {
            polyline fill="none" stroke="currentColor" stroke-width="3" points=(line) {}
        } small {(points.len()) " readings · " (first.0.date()) " – " (last.0.date()) " · range " (min) "–" (max) " " (metric.unit)}}
    }})
}
