//! Account-owned measurements, journal entries, source connections and documents.
use crate::{AppError, SharedState, views};
use app_auth::CurrentUser;
use axum::extract::{Form, Multipart, Path, State};
use axum::http::{StatusCode, header};
use axum::response::{IntoResponse, Redirect, Response};
use maud::{Markup, html};
use ring::aead::{AES_256_GCM, Aad, LessSafeKey, Nonce, UnboundKey};
use serde::Deserialize;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use sqlx::PgPool;
use std::collections::BTreeMap;
use time::OffsetDateTime;
use time::format_description::well_known::Rfc3339;
use uuid::Uuid;

const KINDS: &[&str] =
    &["Measurement", "Medicine", "Supplement", "Workout", "Check-in", "Symptom", "Lab", "Nutrition", "Sleep", "Note"];
const QUICK_FILTERS: &[&str] = &["Measurement", "Workout", "Medicine", "Sleep", "Lab", "Nutrition", "Check-in"];
const MAX_FILE: usize = 32 * 1024 * 1024;
pub const BODY_LIMIT: usize = MAX_FILE + 64 * 1024;

fn invalid(message: &str) -> AppError {
    AppError::Status(StatusCode::UNPROCESSABLE_ENTITY, message.into())
}
fn home() -> Response {
    Redirect::to("/dashboard").into_response()
}
fn to_sources() -> Response {
    Redirect::to("/sources").into_response()
}

#[derive(Deserialize, Default)]
pub struct Filter {
    pub metric: Option<Uuid>,
    #[serde(default)]
    pub range: String,
    #[serde(default)]
    pub q: String,
    #[serde(default)]
    pub offset: i64,
}

pub async fn records(pool: &PgPool, owner: Uuid, term: &str, offset: i64) -> Result<Vec<Value>, sqlx::Error> {
    let rows = sqlx::query!(
        "SELECT id, occurred_at, kind, title, value, unit, note, source FROM health_records
        WHERE user_id=$1 AND ($2='' OR strpos(lower(concat_ws(' ',kind,title,value,unit,note,source)),lower($2))>0)
        ORDER BY occurred_at DESC, id DESC LIMIT 50 OFFSET $3",
        owner,
        term,
        offset.clamp(0, 100_000_000)
    )
    .fetch_all(pool)
    .await?;
    Ok(rows.into_iter().map(|r| json!({"id":r.id,"time":r.occurred_at.format(&Rfc3339).unwrap_or_default(),"kind":r.kind,"title":r.title,"value":r.value,"unit":r.unit,"note":r.note,"source":r.source})).collect())
}

pub async fn dashboard(state: &SharedState, user: &app_auth::User, filter: Filter) -> Result<Response, AppError> {
    let range = crate::trends::Range::parse(&filter.range);
    let overview = crate::trends::overview(&state.pool, user.id, filter.metric, range).await?;
    let courses = crate::regimens::list(&state.pool, user.id, None).await?;
    let rows = records(&state.pool, user.id, &filter.q, filter.offset).await?;
    let files = sqlx::query!(
        "SELECT id,name,status,purpose FROM health_files WHERE user_id=$1 ORDER BY created_at DESC LIMIT 100",
        user.id
    )
    .fetch_all(&state.pool)
    .await?;
    let connection = sqlx::query_scalar!("SELECT status FROM health_connections WHERE user_id=$1", user.id)
        .fetch_optional(&state.pool)
        .await?;
    let has_key = sqlx::query_scalar!("SELECT user_id FROM health_ingest_tokens WHERE user_id=$1", user.id)
        .fetch_optional(&state.pool)
        .await?
        .is_some();
    let clouds = sqlx::query!("SELECT provider,status FROM health_cloud_sources WHERE user_id=$1", user.id)
        .fetch_all(&state.pool)
        .await?;
    let hour = sqlx::query_scalar!("SELECT hour_utc FROM health_reminders WHERE user_id=$1", user.id)
        .fetch_optional(&state.pool)
        .await?;

    let active_courses: Vec<&Value> = courses.iter().filter(|c| c["end"].is_null()).collect();
    let past_courses: Vec<&Value> = courses.iter().filter(|c| !c["end"].is_null()).collect();
    let cloud_ids: Vec<&str> = clouds.iter().map(|c| c.provider.as_str()).collect();
    let connected_names = connected_source_names(connection.is_some(), &cloud_ids, has_key);
    let document_count = files.iter().filter(|f| f.purpose == "document").count();
    let category_summary = summarize_categories(&rows);
    let has_any_data = overview.total_metrics > 0 || !rows.is_empty() || !courses.is_empty() || !files.is_empty();
    let timeline_active = !filter.q.is_empty() || filter.offset > 0;

    let body = html! {
        section class="ledger" data-default-tab=(if timeline_active { "timeline" } else { "overview" }) {
            header class="ledger-header" {
                div {
                    h1 { "Health journal" }
                    p class="muted" { "Your measurements, medicines, workouts and documents in one place." }
                }
                div class="ledger-header-actions" {
                    a class="btn btn-primary" href="#entry" { "+ Add entry" }
                }
            }

            nav class="ledger-nav" aria-label="Journal sections" {
                a href="#overview" { "Overview" }
                a href="#timeline" { "Timeline" }
                a href="#entry" { "Add entry" }
                a href="#medicines" {
                    "Medicines"
                    @if !active_courses.is_empty() { span class="nav-count" { (active_courses.len()) } }
                }
                a href="#files" {
                    "Documents"
                    @if document_count > 0 { span class="nav-count" { (document_count) } }
                }
                a href="#reminders" { "Reminders" }
            }

            section id="overview" class="ledger-panel" {
                div class="summary-strip" {
                    div class="summary-card" {
                        span class="summary-label" { "Tracked metrics" }
                        strong class="summary-value" { (overview.total_metrics) }
                        span class="summary-meta" {
                            @if let Some(d) = overview.latest_date {
                                "Latest " (crate::trends::fmt_full_date(d))
                            } @else {
                                "No measurements yet"
                            }
                        }
                    }
                    a class="summary-card summary-card-link" href="#medicines" {
                        span class="summary-label" { "Active medicines" }
                        strong class="summary-value" { (active_courses.len()) }
                        span class="summary-meta" {
                            @if active_courses.is_empty() {
                                "No ongoing courses"
                            } @else {
                                (active_courses.iter().take(2).map(|c| txt(c, "name")).collect::<Vec<_>>().join(", "))
                                @if active_courses.len() > 2 { " +" (active_courses.len() - 2) }
                            }
                        }
                    }
                    a class="summary-card summary-card-link" href="#timeline" {
                        span class="summary-label" { "Recent entries" }
                        strong class="summary-value" {
                            (rows.len())
                            @if rows.len() == 50 { "+" }
                        }
                        span class="summary-meta" {
                            @if category_summary.is_empty() {
                                "Start logging or importing"
                            } @else {
                                (category_summary)
                            }
                        }
                    }
                    a class="summary-card summary-card-link" href="/sources" {
                        span class="summary-label" { "Connected sources" }
                        strong class="summary-value" { (connected_names.len()) }
                        span class="summary-meta" {
                            @if connected_names.is_empty() {
                                "Connect devices or upload →"
                            } @else {
                                (connected_names.join(" · "))
                            }
                        }
                    }
                }

                @if !has_any_data {
                    div class="card empty-hero" {
                        h2 { "Bring your health history together" }
                        p class="muted" {
                            "Connect a smart scale, fitness app, or phone exporter to build automatic trends — or log your first measurement by hand."
                        }
                        div class="empty-hero-actions" {
                            a class="btn btn-primary" href="/sources" { "Connect a data source" }
                            a class="btn" href="#entry" { "Add your first entry" }
                        }
                    }
                } @else {
                    (overview.chart)
                    (overview.metrics_grid)

                    div class="overview-split" {
                        div class="card overview-card" {
                            div class="card-head-row" {
                                h2 class="card-title" { "Recent activity" }
                                a class="card-action" href="#timeline" { "Full timeline →" }
                            }
                            @if rows.is_empty() {
                                p class="muted small" { "No entries recorded yet." }
                            } @else {
                                ul class="compact-list" {
                                    @for row in rows.iter().take(6) {
                                        li class="compact-row" {
                                            div class="compact-main" {
                                                span class=(kind_class(txt(row, "kind"))) { (txt(row, "kind")) }
                                                span class="compact-title" { (txt(row, "title")) }
                                            }
                                            div class="compact-meta" {
                                                @if !txt(row, "value").is_empty() {
                                                    strong class="compact-val" {
                                                        (txt(row, "value"))
                                                        @if !txt(row, "unit").is_empty() { " " (txt(row, "unit")) }
                                                    }
                                                }
                                                small class="muted" { time datetime=(txt(row, "time")) { (txt(row, "time")) } }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        div class="card overview-card" {
                            div class="card-head-row" {
                                h2 class="card-title" { "Active medicines & supplements" }
                                a class="card-action" href="#medicines" { "Manage →" }
                            }
                            @if active_courses.is_empty() {
                                p class="muted small" { "No active courses right now. Track prescriptions and daily supplements in Medicines." }
                            } @else {
                                ul class="compact-list" {
                                    @for c in active_courses.iter().take(6) {
                                        li class="compact-row" {
                                            div class="compact-main" {
                                                span class="status-dot status-dot-active" aria-hidden="true" {}
                                                span class="compact-title" { (txt(c, "name")) }
                                            }
                                            div class="compact-meta" {
                                                @if !txt(c, "dose").is_empty() {
                                                    strong class="compact-val" { (txt(c, "dose")) }
                                                }
                                                @if !txt(c, "schedule").is_empty() {
                                                    small class="muted" { (txt(c, "schedule")) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            section id="timeline" class="ledger-panel" {
                div class="section-bar" {
                    div {
                        h2 class="section-heading" { "Timeline" }
                        p class="muted small" { "Search across every measurement, workout, medicine, lab result and note." }
                    }
                }
                form method="get" action="/dashboard#timeline" class="ledger-search" {
                    input name="q" aria-label="Search records" placeholder="Search by name, source, category or note" value=(filter.q);
                    button class="btn btn-primary" type="submit" { "Search" }
                    @if !filter.q.is_empty() {
                        a class="btn" href="/dashboard#timeline" { "Clear" }
                    }
                }
                div class="filter-chips" role="navigation" aria-label="Quick category filters" {
                    a class=(if filter.q.is_empty() { "filter-chip is-active" } else { "filter-chip" }) href="/dashboard#timeline" { "All" }
                    @for kind in QUICK_FILTERS {
                        @let active = filter.q.eq_ignore_ascii_case(kind);
                        a class=(if active { "filter-chip is-active" } else { "filter-chip" }) href={ "/dashboard?q=" (kind) "#timeline" } { (kind) }
                    }
                }
                @if rows.is_empty() {
                    div class="card empty-state" {
                        p {
                            @if filter.q.is_empty() { "Your first entry will appear here." }
                            @else { "No matching records for \"" (filter.q) "\"." }
                        }
                    }
                } @else {
                    div class="card record-list" {
                        @for row in &rows {
                            article class="ledger-row" {
                                div class="record-main" {
                                    div class="record-tags" {
                                        span class=(kind_class(txt(row, "kind"))) { (txt(row, "kind")) }
                                        span class="source-pill" { (txt(row, "source")) }
                                    }
                                    h3 { (txt(row, "title")) }
                                    @if !txt(row, "note").is_empty() {
                                        p class="record-note" { (txt(row, "note")) }
                                    }
                                }
                                div class="record-values" {
                                    @if !txt(row, "value").is_empty() {
                                        strong class="record-metric" {
                                            (txt(row, "value"))
                                            @if !txt(row, "unit").is_empty() { span class="muted" { " " (txt(row, "unit")) } }
                                        }
                                    }
                                    small class="record-time" { time datetime=(txt(row, "time")) { (txt(row, "time")) } }
                                }
                                form class="record-delete" method="post" action={ "/health/records/" (txt(row, "id")) "/delete" } {
                                    button class="btn btn-subtle btn-sm" type="submit" { "Delete" }
                                }
                            }
                        }
                    }
                }
                @if filter.offset > 0 || rows.len() == 50 {
                    div class="pager-row" {
                        @if filter.offset > 0 { (page_link(&filter.q, (filter.offset - 50).max(0), "← Newer")) }
                        @if rows.len() == 50 { (page_link(&filter.q, filter.offset.saturating_add(50), "Older →")) }
                    }
                }
            }

            section id="entry" class="ledger-panel" {
                div class="card form-card" {
                    div class="panel-head" {
                        h2 { "Add an entry" }
                        p class="muted small" { "Log a measurement, medicine dose, workout, symptom, lab value or quick note." }
                    }
                    form class="ledger-form" method="post" action="/health/records" {
                        div class="ledger-pair" {
                            label { "Category" select name="kind" id="record-kind" { @for k in KINDS { option { (k) } } } }
                            label { "When" input type="datetime-local" id="local-time"; }
                        }
                        input type="hidden" name="at" id="record-time";
                        label { "Name" input required name="title" maxlength="160" placeholder="e.g. Weight, Vitamin D or Bench press"; }
                        div class="ledger-pair" {
                            label { "Value or dose" input name="value" maxlength="160" placeholder="e.g. 72.4 or 3 × 10"; }
                            label { "Unit" input name="unit" maxlength="64" placeholder="e.g. kg, mg or reps"; }
                        }
                        label { "Notes" textarea id="record-note" name="note" maxlength="4000" rows="3" placeholder="Symptoms, mood, dosage timing or workout details" {} }
                        div class="form-actions" {
                            button class="btn btn-primary" type="submit" { "Save entry" }
                            button type="button" class="btn" hidden id="dictate" { "Dictate a note" }
                            span role="status" class="muted small" id="voice-state" {}
                        }
                    }
                }
            }

            section id="medicines" class="ledger-panel" {
                div class="section-bar" {
                    div {
                        h2 class="section-heading" { "Medicines and supplements" }
                        p class="muted small" { "Keep track of ongoing regimens and past courses." }
                    }
                }
                div class="card form-card course-add-card" {
                    details open[courses.is_empty()] {
                        summary { "Add a course" }
                        form class="ledger-form" method="post" action="/health/medicines" {
                            label { "Medicine or supplement" input required name="name" maxlength="160" placeholder="e.g. Vitamin D3, Creatine or Magnesium"; }
                            div class="ledger-pair" {
                                label { "Dose" input name="dose" maxlength="160" placeholder="e.g. 500 mg"; }
                                label { "Schedule" input name="schedule" maxlength="250" placeholder="e.g. every morning"; }
                            }
                            div class="ledger-pair" {
                                label { "First day" input type="date" required name="starts"; }
                                label { "Last day (optional)" input type="date" name="ends"; }
                            }
                            button class="btn btn-primary" type="submit" { "Save course" }
                        }
                    }
                }
                @if !active_courses.is_empty() {
                    h3 class="sub-heading" { "Active courses" }
                    div class="card record-list" {
                        @for c in &active_courses {
                            article class="ledger-row course-row" {
                                div class="record-main" {
                                    div class="record-tags" {
                                        span class="status-pill status-pill-ok" { "Active" }
                                        small { "Started " (txt(c, "start")) }
                                    }
                                    h3 { (txt(c, "name")) }
                                    p {
                                        (txt(c, "dose"))
                                        @if !txt(c, "dose").is_empty() && !txt(c, "schedule").is_empty() { " · " }
                                        (txt(c, "schedule"))
                                    }
                                }
                                div class="course-actions" {
                                    form class="inline-end-form" method="post" action={ "/health/medicines/" (txt(c, "id")) "/end" } {
                                        label class="inline-date-label" {
                                            span { "Last day" }
                                            input type="date" required name="ends" min=(txt(c, "start"));
                                        }
                                        button class="btn btn-sm" type="submit" { "End course" }
                                    }
                                    form method="post" action={ "/health/medicines/" (txt(c, "id")) "/delete" } {
                                        button class="btn btn-subtle btn-sm" type="submit" { "Delete course" }
                                    }
                                }
                            }
                        }
                    }
                }
                @if !past_courses.is_empty() {
                    h3 class="sub-heading" { "Completed courses" }
                    div class="card record-list" {
                        @for c in &past_courses {
                            article class="ledger-row course-row" {
                                div class="record-main" {
                                    div class="record-tags" {
                                        span class="status-pill status-pill-muted" { "Ended" }
                                        small { (txt(c, "start")) " – " (txt(c, "end")) }
                                    }
                                    h3 { (txt(c, "name")) }
                                    p {
                                        (txt(c, "dose"))
                                        @if !txt(c, "dose").is_empty() && !txt(c, "schedule").is_empty() { " · " }
                                        (txt(c, "schedule"))
                                    }
                                }
                                form method="post" action={ "/health/medicines/" (txt(c, "id")) "/delete" } {
                                    button class="btn btn-subtle btn-sm" type="submit" { "Delete course" }
                                }
                            }
                        }
                    }
                }
            }

            section id="files" class="ledger-panel" {
                div class="section-bar" {
                    div {
                        h2 class="section-heading" { "Documents" }
                        p class="muted small" { "Keep original lab reports and extract searchable text from PDFs, images and text files. Maximum 32 MiB per file." }
                    }
                }
                div class="card form-card" {
                    (upload_form("document"))
                }
                @if files.is_empty() {
                    div class="card empty-state" {
                        p { "No uploaded documents or import archives yet." }
                    }
                } @else {
                    div class="card record-list" {
                        @for f in files {
                            div class="ledger-row file-row" {
                                div class="record-main" {
                                    div class="record-tags" {
                                        span class=(if f.purpose == "import" { "source-pill" } else { "kind-badge kind-lab" }) { (f.purpose) }
                                        span class=(file_status_class(&f.status)) { (f.status) }
                                    }
                                    h3 { a class="file-link" href={ "/health/files/" (f.id) } { (f.name) } }
                                }
                                div class="file-actions" {
                                    a class="btn btn-sm" href={ "/health/files/" (f.id) "/download" } { "Download" }
                                    form method="post" action={ "/health/files/" (f.id) "/delete" } {
                                        button class="btn btn-subtle btn-sm" type="submit" { "Delete file" }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            section id="reminders" class="ledger-panel" {
                div class="card form-card" {
                    div class="card-head-row" {
                        div {
                            h2 { "Daily check-in reminder" }
                            p class="muted small" { "Receive a gentle daily email prompt to log how you feel." }
                        }
                        @if let Some(h) = hour {
                            span class="status-pill status-pill-ok" { (format!("{h:02}:00 UTC")) }
                        } @else {
                            span class="status-pill status-pill-muted" { "Off" }
                        }
                    }
                    form class="ledger-form" method="post" action="/health/reminder" {
                        label {
                            "Email schedule"
                            select name="hour" {
                                option value="-1" selected[hour.is_none()] { "Off" }
                                @for h in 0..24 {
                                    option value=(h) selected[hour == Some(h)] { (format!("{h:02}:00 UTC")) }
                                }
                            }
                        }
                        button class="btn btn-primary" type="submit" { "Save schedule" }
                    }
                }
            }
        }
        script defer src=(state.asset_url("health.js")) {}
    };
    let page = views::Page { title: "Health journal", path: "/dashboard", user: Some(user), body };
    Ok(([(header::CACHE_CONTROL, "private, no-store")], axum::response::Html(views::layout(state, page).into_string()))
        .into_response())
}

/// Dedicated Data sources page linked from the account profile menu.
pub async fn sources(state: &SharedState, user: &app_auth::User) -> Result<Response, AppError> {
    let connection = sqlx::query_scalar!("SELECT status FROM health_connections WHERE user_id=$1", user.id)
        .fetch_optional(&state.pool)
        .await?;
    let has_key = sqlx::query_scalar!("SELECT user_id FROM health_ingest_tokens WHERE user_id=$1", user.id)
        .fetch_optional(&state.pool)
        .await?
        .is_some();
    let clouds = sqlx::query!("SELECT provider,status FROM health_cloud_sources WHERE user_id=$1", user.id)
        .fetch_all(&state.pool)
        .await?;
    let files = sqlx::query!(
        "SELECT id,name,status,purpose FROM health_files WHERE user_id=$1 ORDER BY created_at DESC LIMIT 100",
        user.id
    )
    .fetch_all(&state.pool)
    .await?;

    let renpho = clouds.iter().find(|x| x.provider == "renpho");
    let renpho_needs_login = renpho.is_none_or(|c| c.status.starts_with("Reconnect"));
    let cloud_ids: Vec<&str> = clouds.iter().map(|c| c.provider.as_str()).collect();
    let connected_count = connected_source_names(connection.is_some(), &cloud_ids, has_key).len();
    let imports: Vec<_> = files.iter().filter(|f| f.purpose == "import").take(12).collect();

    let body = html! {
        section class="page settings-page sources-page" {
            header class="sources-header" {
                div {
                    h1 { "Data sources" }
                    p class="lede" { "Connect automatic syncs, configure on-device phone exporters, or upload archive files." }
                }
                div class="sources-header-actions" {
                    @if connected_count > 0 {
                        span class="status-pill status-pill-ok" {
                            (connected_count) " " (if connected_count == 1 { "active connection" } else { "active connections" })
                        }
                    }
                    a class="btn" href="/dashboard" { "← Back to dashboard" }
                }
            }

            div class="settings-layout" {
                nav class="settings-toc" aria-label="Data source sections" {
                    div class="settings-toc-group" {
                        p class="settings-toc-title" { "Connections" }
                        ul {
                            li { a href="#direct-sync" { "Direct sync" } }
                            li { a href="#phone-exporters" { "Phone exporters" } }
                            li { a href="#manual-import" { "File import" } }
                        }
                    }
                    div class="settings-toc-group" {
                        p class="settings-toc-title" { "Reference" }
                        ul {
                            li { a href="#format-guide" { "Format guide" } }
                        }
                    }
                }

                div class="settings-sections sources-sections" {
                    section class="settings-group" id="direct-sync" aria-labelledby="direct-sync-heading" {
                        h2 class="settings-group-title" id="direct-sync-heading" { "Direct sync" }
                        p class="muted small group-desc" { "Automatic background imports that pull your latest readings on a schedule." }

                        div class="sources-stack" {
                            article class="card source-card" id="source-renpho" {
                                div class="source-card-head" {
                                    div class="source-card-title" {
                                        span class="source-icon" aria-hidden="true" { "R" }
                                        div {
                                            h3 { "RENPHO scale and smart tape" }
                                            p class="muted small" { "Body weight, composition and girth tape readings refreshed every six hours." }
                                        }
                                    }
                                    @if let Some(cloud) = renpho {
                                        span class=(source_status_class(&cloud.status)) {
                                            (if cloud.status.starts_with("Reconnect") { "Reconnect needed" } else { "Connected" })
                                        }
                                    } @else {
                                        span class="status-pill status-pill-muted" { "Not connected" }
                                    }
                                }
                                div class="source-callout" {
                                    p class="small" {
                                        "Connecting through the unofficial RENPHO client signs you out of the RENPHO app. Signing back into the app can pause sync here. If your Bluetooth scale needs the phone app to upload weigh-ins, consider the Apple Health exporter below."
                                    }
                                }
                                @if let Some(cloud) = renpho {
                                    div class="source-status-bar" {
                                        p class="small" { (cloud.status) }
                                        form method="post" action="/health/cloud/renpho/disconnect" {
                                            button class="btn btn-sm" type="submit" { "Disconnect RENPHO" }
                                        }
                                    }
                                }
                                @if renpho_needs_login {
                                    form class="form source-form" method="post" action="/health/renpho/connect" {
                                        div class="ledger-pair" {
                                            label { "RENPHO email" input type="email" required name="email" maxlength="320" autocomplete="off" placeholder="you@example.com"; }
                                            label { "RENPHO password" input type="password" required name="password" maxlength="1024" autocomplete="off"; }
                                        }
                                        p class="hint" { "The worker uses your password once and keeps an encrypted session for scheduled reads." }
                                        button class="btn btn-primary" type="submit" { "Connect RENPHO" }
                                    }
                                }
                            }

                            article class="card source-card" id="source-intervals" {
                                div class="source-card-head" {
                                    div class="source-card-title" {
                                        span class="source-icon" aria-hidden="true" { "I" }
                                        div {
                                            h3 { "Intervals.icu" }
                                            p class="muted small" { "Daily wellness metrics and training activities refreshed every six hours." }
                                        }
                                    }
                                    @if connection.is_some() {
                                        span class="status-pill status-pill-ok" { "Connected" }
                                    } @else {
                                        span class="status-pill status-pill-muted" { "Not connected" }
                                    }
                                }
                                @if let Some(status) = &connection {
                                    div class="source-status-bar" {
                                        p class="small" { (status) }
                                        form method="post" action="/health/disconnect" {
                                            button class="btn btn-sm" type="submit" { "Disconnect" }
                                        }
                                    }
                                } @else {
                                    form class="form source-form" method="post" action="/health/connect" {
                                        label {
                                            "Intervals.icu API key"
                                            input type="password" required name="token" maxlength="256" autocomplete="off" placeholder="Paste your personal API key";
                                        }
                                        button class="btn btn-primary" type="submit" { "Connect Intervals.icu" }
                                    }
                                }
                            }

                            div class="oauth-provider-grid" {
                                @for provider in crate::health_oauth::PROVIDERS {
                                    @let cloud = clouds.iter().find(|x| x.provider == provider.id);
                                    article class="card source-card source-card-compact" {
                                        div class="source-card-head" {
                                            div class="source-card-title" {
                                                span class="source-icon" aria-hidden="true" { (provider_monogram(provider.id)) }
                                                div {
                                                    h3 { (provider.title) }
                                                    p class="muted small" { (provider_summary(provider.id)) }
                                                }
                                            }
                                            @if let Some(c) = cloud {
                                                span class=(source_status_class(&c.status)) {
                                                    (if c.status.starts_with("Reconnect") { "Action needed" } else { "Connected" })
                                                }
                                            } @else if provider.configured() {
                                                span class="status-pill status-pill-muted" { "Available" }
                                            } @else {
                                                span class="status-pill status-pill-muted" { "File import" }
                                            }
                                        }
                                        div class="source-card-foot" {
                                            @if let Some(c) = cloud {
                                                p class="small muted" { (c.status) }
                                                form method="post" action={ "/health/cloud/" (provider.id) "/disconnect" } {
                                                    button class="btn btn-sm" type="submit" { "Disconnect" }
                                                }
                                            } @else if provider.configured() {
                                                form method="post" action={ "/health/cloud/" (provider.id) "/connect" } {
                                                    button class="btn btn-primary btn-sm" type="submit" { "Connect " (provider.title) }
                                                }
                                            } @else {
                                                p class="small muted" { "Direct sync requires operator setup. You can import an export below." }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    section class="settings-group" id="phone-exporters" aria-labelledby="phone-exporters-heading" {
                        h2 class="settings-group-title" id="phone-exporters-heading" { "Phone exporters" }
                        p class="muted small group-desc" { "Push scheduled batches straight from Apple Health or Android Health Connect using a write-only key." }

                        article class="card source-card" {
                            div class="source-card-head" {
                                div {
                                    h3 { "Automated device export" }
                                    p class="muted small" { "Generate a dedicated bearer token that can only submit new health records." }
                                }
                                @if has_key {
                                    span class="status-pill status-pill-ok" { "Key active" }
                                } @else {
                                    span class="status-pill status-pill-muted" { "No key yet" }
                                }
                            }
                            div class="exporter-grid" {
                                div class="exporter-box" {
                                    h4 { "Android · Health Connect" }
                                    p class="small muted" {
                                        "Enable Health Connect sharing in your source apps. In "
                                        a href="https://github.com/mcnaveen/health-connect-webhook" rel="noopener noreferrer" { "Health Connect Webhook" }
                                        ", add an HTTP webhook with your endpoint URL and Authorization header, pick JSON and the metrics to share, sync history, then turn on scheduled sync."
                                    }
                                }
                                div class="exporter-box" {
                                    h4 { "iOS · Apple Health" }
                                    p class="small muted" {
                                        "In Health Auto Export, create a REST API automation, choose JSON, and paste the endpoint and Authorization header shown when you generate a key. Run a historical export first, then enable scheduling."
                                    }
                                }
                            }
                            div class="source-actions-row" {
                                form method="post" action="/health/exporter" {
                                    button class="btn btn-primary" type="submit" {
                                        @if has_key { "Replace exporter key" } @else { "Create exporter key" }
                                    }
                                }
                                @if has_key {
                                    form method="post" action="/health/exporter/revoke" {
                                        button class="btn btn-danger" type="submit" { "Revoke exporter key" }
                                    }
                                }
                            }
                        }
                    }

                    section class="settings-group" id="manual-import" aria-labelledby="manual-import-heading" {
                        h2 class="settings-group-title" id="manual-import-heading" { "Manual file import" }
                        p class="muted small group-desc" { "Upload archives or CSVs from any health app — up to 32 MiB per file." }

                        article class="card source-card" {
                            div class="panel-head" {
                                h3 { "Import an export" }
                                p class="muted small" {
                                    "Supports Apple Health export.zip or export.xml, Strava activities.csv, Fitbit JSON, Samsung Health exports, FHIR labs, or a dated CSV. Column headings become metric names; include units in headings and ISO dates with offsets when available."
                                }
                            }
                            (upload_form("import"))

                            @if !imports.is_empty() {
                                div class="recent-imports" {
                                    h4 { "Recent imports" }
                                    div class="admin-table-wrap" {
                                        table class="admin-table" {
                                            thead { tr { th { "File" } th { "Status" } } }
                                            tbody {
                                                @for item in imports {
                                                    tr {
                                                        th scope="row" { (item.name) }
                                                        td { span class=(file_status_class(&item.status)) { (item.status) } }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    section class="settings-group" id="format-guide" aria-labelledby="format-guide-heading" {
                        h2 class="settings-group-title" id="format-guide-heading" { "Format guide" }
                        p class="muted small group-desc" { "Tips for exporting clean history from common devices and apps." }

                        div class="guide-grid" {
                            div class="card guide-card" {
                                h3 { "RENPHO" }
                                p class="small muted" { "Use Direct sync above, export dated scale readings as CSV, or sync RENPHO to Apple Health and use the phone exporter." }
                            }
                            div class="card guide-card" {
                                h3 { "PICOOC, OPPO OHealth & Eight Sleep" }
                                p class="small muted" { "Bring across measurements already synced into Apple Health, or convert a personal export into the custom CSV schema." }
                            }
                            div class="card guide-card" {
                                h3 { "Samsung Health" }
                                p class="small muted" { "Upload the ZIP or a dated measurement CSV from Download personal data. Numeric columns become records; verify units against the app." }
                            }
                            div class="card guide-card" {
                                h3 { "Strava, Strong & Hevy" }
                                p class="small muted" { "Upload activities.csv from a Strava archive or an exercise-and-reps CSV from your workout tracker." }
                            }
                            div class="card guide-card" {
                                h3 { "FatSecret & nutrition" }
                                p class="small muted" { "Upload a dated CSV of calories or nutrients here, or keep full diary reports under Documents." }
                            }
                            div class="card guide-card" {
                                h3 { "Lab results" }
                                p class="small muted" { "FHIR Observation JSON imports values, units and reference ranges. Upload PDF or scanned lab reports under Documents." }
                            }
                            div class="card guide-card guide-card-wide" {
                                h3 { "Custom CSV schema" }
                                p class="small muted" { "Any UTF-8 CSV with these columns imports directly into your timeline:" }
                                pre class="code-snippet" { code { "timestamp,metric,value,unit,kind,note" } }
                            }
                        }
                    }
                }
            }
        }
    };

    let page = views::Page { title: "Data sources", path: "/sources", user: Some(user), body };
    Ok(([(header::CACHE_CONTROL, "private, no-store")], axum::response::Html(views::layout(state, page).into_string()))
        .into_response())
}

fn txt<'a>(row: &'a Value, key: &str) -> &'a str {
    row[key].as_str().unwrap_or("")
}

fn kind_class(kind: &str) -> &'static str {
    match kind {
        "Measurement" => "kind-badge kind-measurement",
        "Medicine" => "kind-badge kind-medicine",
        "Supplement" => "kind-badge kind-supplement",
        "Workout" => "kind-badge kind-workout",
        "Sleep" => "kind-badge kind-sleep",
        "Lab" => "kind-badge kind-lab",
        "Nutrition" => "kind-badge kind-nutrition",
        "Symptom" => "kind-badge kind-symptom",
        "Check-in" => "kind-badge kind-checkin",
        _ => "kind-badge kind-note",
    }
}

fn file_status_class(status: &str) -> &'static str {
    let lower = status.to_ascii_lowercase();
    if lower.contains("failed") || lower.contains("error") {
        "status-pill status-pill-warn"
    } else if lower.contains("queued") || lower.contains("waiting") {
        "status-pill status-pill-muted"
    } else {
        "status-pill status-pill-ok"
    }
}

fn source_status_class(status: &str) -> &'static str {
    if status.starts_with("Reconnect") || status.starts_with("Could not") {
        "status-pill status-pill-warn"
    } else {
        "status-pill status-pill-ok"
    }
}

fn provider_label(id: &str) -> &str {
    match id {
        "renpho" => "RENPHO",
        "hevy" => "Hevy",
        "google-health" => "Google Health",
        "withings" => "Withings",
        other => other,
    }
}

fn provider_monogram(id: &str) -> &'static str {
    match id {
        "hevy" => "H",
        "google-health" => "G",
        "withings" => "W",
        _ => "S",
    }
}

fn provider_summary(id: &str) -> &'static str {
    match id {
        "hevy" => "Strength workouts, exercises, sets and reps.",
        "google-health" => "Activity, heart rate, sleep, weight and Fitbit metrics.",
        "withings" => "Smart scale weight, body composition and blood pressure.",
        _ => "Automatic scheduled sync.",
    }
}

fn connected_source_names(has_intervals: bool, cloud_providers: &[&str], has_key: bool) -> Vec<String> {
    let mut names = Vec::new();
    if has_intervals {
        names.push("Intervals.icu".to_owned());
    }
    for provider in cloud_providers {
        names.push(provider_label(provider).to_owned());
    }
    if has_key {
        names.push("Phone exporter".to_owned());
    }
    names
}

fn summarize_categories(rows: &[Value]) -> String {
    let mut counts: BTreeMap<&str, usize> = BTreeMap::new();
    for r in rows {
        let k = txt(r, "kind");
        if !k.is_empty() {
            *counts.entry(k).or_insert(0) += 1;
        }
    }
    let mut sorted: Vec<(&str, usize)> = counts.into_iter().collect();
    sorted.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(b.0)));
    sorted.into_iter().take(3).map(|(k, n)| format!("{n} {k}")).collect::<Vec<_>>().join(" · ")
}

fn page_link(q: &str, offset: i64, label: &str) -> Markup {
    html! {
        form method="get" action="/dashboard#timeline" {
            input type="hidden" name="q" value=(q);
            input type="hidden" name="offset" value=(offset);
            button class="btn" type="submit" { (label) }
        }
    }
}

fn upload_form(purpose: &str) -> Markup {
    html! {
        form class="form ledger-form" method="post" action="/health/files" enctype="multipart/form-data" {
            input type="hidden" name="purpose" value=(purpose);
            @if purpose == "import" {
                label {
                    "Source name"
                    input name="source" required maxlength="80" placeholder="e.g. RENPHO, Samsung Health or FatSecret";
                }
            }
            label {
                "Choose file"
                input name="file" type="file" required;
            }
            button class="btn btn-primary" type="submit" {
                @if purpose == "import" { "Upload & queue import" } @else { "Upload document" }
            }
        }
    }
}

#[derive(Deserialize)]
pub struct Entry {
    kind: String,
    title: String,
    value: String,
    unit: String,
    note: String,
    at: String,
}
pub async fn add(
    State(s): State<SharedState>,
    CurrentUser(u): CurrentUser,
    Form(e): Form<Entry>,
) -> Result<Response, AppError> {
    let title = e.title.trim();
    if !KINDS.contains(&e.kind.as_str())
        || title.is_empty()
        || title.len() > 640
        || e.value.len() > 640
        || e.unit.len() > 256
        || e.note.len() > 16000
    {
        return Err(invalid("Check the entry name, category and field lengths."));
    }
    let at = if e.at.is_empty() {
        OffsetDateTime::now_utc()
    } else {
        OffsetDateTime::parse(&e.at, &Rfc3339).map_err(|_| invalid("Invalid date or time."))?
    };
    sqlx::query!("INSERT INTO health_records(user_id,occurred_at,kind,title,value,unit,note,source) VALUES($1,$2,$3,$4,$5,$6,$7,'Manual')",u.id,at,e.kind,title,e.value,e.unit,e.note).execute(&s.pool).await?;
    Ok(home())
}
pub async fn delete_record(
    State(s): State<SharedState>,
    CurrentUser(u): CurrentUser,
    Path(id): Path<Uuid>,
) -> Result<Response, AppError> {
    sqlx::query!("DELETE FROM health_records WHERE user_id=$1 AND id=$2", u.id, id).execute(&s.pool).await?;
    Ok(Redirect::to("/dashboard#timeline").into_response())
}
pub async fn upload(
    State(s): State<SharedState>,
    CurrentUser(u): CurrentUser,
    mut parts: Multipart,
) -> Result<Response, AppError> {
    let (mut purpose, mut source, mut file) = (String::new(), String::new(), None);
    while let Some(part) = parts.next_field().await.map_err(|_| invalid("Invalid upload or file over 32 MiB."))? {
        match part.name() {
            Some("purpose") => purpose = part.text().await?,
            Some("source") => source = part.text().await?,
            Some("file") => {
                let name = part
                    .file_name()
                    .unwrap_or("document")
                    .chars()
                    .filter(|c| !c.is_control())
                    .take(200)
                    .collect::<String>();
                let data = part.bytes().await.map_err(|_| invalid("File exceeds upload limit."))?;
                if data.is_empty() || data.len() > MAX_FILE {
                    return Err(invalid("Choose a non-empty file up to 32 MiB."));
                }
                file = Some((name, data));
            }
            _ => {}
        }
    }
    let (name, bytes) = file.ok_or_else(|| invalid("Choose a file."))?;
    if !["import", "document"].contains(&purpose.as_str())
        || source.len() > 320
        || (purpose == "import" && source.trim().is_empty())
    {
        return Err(invalid("Choose a source and upload type."));
    }
    sqlx::query!(
        "INSERT INTO health_files(user_id,name,purpose,source,content) VALUES($1,$2,$3,$4,$5)",
        u.id,
        name,
        purpose,
        source.trim(),
        bytes.as_ref()
    )
    .execute(&s.pool)
    .await?;
    if purpose == "import" {
        Ok(Redirect::to("/sources#manual-import").into_response())
    } else {
        Ok(Redirect::to("/dashboard#files").into_response())
    }
}
pub async fn file(
    State(s): State<SharedState>,
    CurrentUser(u): CurrentUser,
    Path(id): Path<Uuid>,
) -> Result<Response, AppError> {
    let f = sqlx::query!("SELECT name,status,extracted FROM health_files WHERE id=$1 AND user_id=$2", id, u.id)
        .fetch_optional(&s.pool)
        .await?
        .ok_or_else(missing)?;
    let body = html! {
        section class="page ledger" {
            header class="ledger-header" {
                div {
                    a class="muted small" href="/dashboard#files" { "← Back to documents" }
                    h1 { (f.name) }
                    span class=(file_status_class(&f.status)) { (f.status) }
                }
                a class="btn btn-primary" href={ "/health/files/" (id) "/download" } { "Download original" }
            }
            div class="card doc-body" {
                @if f.extracted.trim().is_empty() {
                    p class="muted" { "No extracted text available for this file." }
                } @else {
                    pre class="ledger-text" { (f.extracted) }
                }
            }
        }
    };
    Ok((
        [(header::CACHE_CONTROL, "private, no-store")],
        axum::response::Html(
            views::layout(&s, views::Page { title: "Document", path: "/health/files", user: Some(&u), body })
                .into_string(),
        ),
    )
        .into_response())
}
fn missing() -> AppError {
    AppError::Status(StatusCode::NOT_FOUND, "File not found.".into())
}
pub async fn download(
    State(s): State<SharedState>,
    CurrentUser(u): CurrentUser,
    Path(id): Path<Uuid>,
) -> Result<Response, AppError> {
    let f = sqlx::query!("SELECT name,content FROM health_files WHERE id=$1 AND user_id=$2", id, u.id)
        .fetch_optional(&s.pool)
        .await?
        .ok_or_else(missing)?;
    let safe: String =
        f.name.chars().map(|c| if c.is_ascii_alphanumeric() || ".-_ ".contains(c) { c } else { '_' }).collect();
    Ok((
        [
            (header::CACHE_CONTROL, "private, no-store".to_string()),
            (header::CONTENT_TYPE, "application/octet-stream".into()),
            (header::CONTENT_DISPOSITION, format!("attachment; filename=\"{safe}\"")),
        ],
        f.content,
    )
        .into_response())
}
pub async fn delete_file(
    State(s): State<SharedState>,
    CurrentUser(u): CurrentUser,
    Path(id): Path<Uuid>,
) -> Result<Response, AppError> {
    sqlx::query!("DELETE FROM health_files WHERE user_id=$1 AND id=$2", u.id, id).execute(&s.pool).await?;
    Ok(Redirect::to("/dashboard#files").into_response())
}
#[derive(Deserialize)]
pub struct Key {
    token: String,
}
pub async fn connect(
    State(s): State<SharedState>,
    CurrentUser(u): CurrentUser,
    Form(k): Form<Key>,
) -> Result<Response, AppError> {
    let token = k.token.trim();
    if token.is_empty() || token.len() > 256 {
        return Err(invalid("Invalid connection key."));
    }
    let data = seal(u.id, token.as_bytes())?;
    sqlx::query!("INSERT INTO health_connections(user_id,credential) VALUES($1,$2) ON CONFLICT(user_id) DO UPDATE SET credential=EXCLUDED.credential,next_sync=now(),backfill_before=NULL,status='Queued'",u.id,data).execute(&s.pool).await?;
    Ok(to_sources())
}
pub async fn disconnect(State(s): State<SharedState>, CurrentUser(u): CurrentUser) -> Result<Response, AppError> {
    sqlx::query!("DELETE FROM health_connections WHERE user_id=$1", u.id).execute(&s.pool).await?;
    Ok(to_sources())
}
#[derive(Deserialize)]
pub struct Schedule {
    hour: i16,
}
pub async fn reminder(
    State(s): State<SharedState>,
    CurrentUser(u): CurrentUser,
    Form(f): Form<Schedule>,
) -> Result<Response, AppError> {
    if f.hour == -1 {
        sqlx::query!("DELETE FROM health_reminders WHERE user_id=$1", u.id).execute(&s.pool).await?;
    } else if (0..24).contains(&f.hour) {
        sqlx::query!("INSERT INTO health_reminders(user_id,hour_utc,next_send) VALUES($1,$2,date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' + $2::smallint * interval '1 hour' + CASE WHEN extract(hour FROM now() AT TIME ZONE 'UTC') >= $2 THEN interval '1 day' ELSE interval '0 days' END) ON CONFLICT(user_id) DO UPDATE SET hour_utc=EXCLUDED.hour_utc,next_send=EXCLUDED.next_send",u.id,f.hour).execute(&s.pool).await?;
    } else {
        return Err(invalid("Choose an hour from 0 to 23."));
    }
    Ok(Redirect::to("/dashboard#reminders").into_response())
}

/// Dedicated write-only credential for on-device exporters.
pub async fn issue_token(State(s): State<SharedState>, CurrentUser(u): CurrentUser) -> Result<Response, AppError> {
    let mut random = [0u8; 32];
    getrandom::fill(&mut random)?;
    let token = format!("hli_{}", hex::encode(random));
    let digest = Sha256::digest(token.as_bytes()).to_vec();
    sqlx::query!("INSERT INTO health_ingest_tokens(user_id,token_hash) VALUES($1,$2) ON CONFLICT(user_id) DO UPDATE SET token_hash=EXCLUDED.token_hash",u.id,digest).execute(&s.pool).await?;
    let body = html! {
        section class="page ledger" {
            header class="ledger-header" {
                div {
                    h1 { "Connect an exporter" }
                    p class="muted" { "Copy these settings into your phone exporter app. This key is shown once." }
                }
                a class="btn" href="/sources#phone-exporters" { "← Back to data sources" }
            }
            div class="card exporter-reveal" {
                dl class="exporter-dl" {
                    dt { "Method" } dd { code { "POST" } }
                    dt { "Endpoint" } dd { pre class="code-snippet" { code { (s.cfg.site_url) "/ingest/v1/health" } } }
                    dt { "Request header" } dd { pre class="code-snippet" { code { "Authorization: Bearer " (token) } } }
                    dt { "Format" } dd { code { "JSON" } }
                }
            }
        }
    };
    Ok((
        [(header::CACHE_CONTROL, "private, no-store")],
        axum::response::Html(
            views::layout(
                &s,
                views::Page { title: "Connect an exporter", path: "/health/exporter", user: Some(&u), body },
            )
            .into_string(),
        ),
    )
        .into_response())
}
pub async fn revoke_token(State(s): State<SharedState>, CurrentUser(u): CurrentUser) -> Result<Response, AppError> {
    sqlx::query!("DELETE FROM health_ingest_tokens WHERE user_id=$1", u.id).execute(&s.pool).await?;
    Ok(to_sources())
}
pub async fn ingest(
    State(s): State<SharedState>,
    headers: axum::http::HeaderMap,
    request: axum::extract::Request,
) -> Result<Response, AppError> {
    let unauthorized = || AppError::Status(StatusCode::UNAUTHORIZED, "Valid exporter key required.".into());
    let token = headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .filter(|v| v.starts_with("hli_") && v.len() == 68)
        .ok_or_else(unauthorized)?;
    let hash = Sha256::digest(token.as_bytes()).to_vec();
    let owner = sqlx::query_scalar!("SELECT user_id FROM health_ingest_tokens WHERE token_hash=$1", hash)
        .fetch_optional(&s.pool)
        .await?
        .ok_or_else(unauthorized)?;
    let raw = axum::body::to_bytes(request.into_body(), 8 * 1024 * 1024)
        .await
        .map_err(|_| invalid("Split batches at 8 MiB."))?;
    let data: Value = serde_json::from_slice(&raw).map_err(|_| invalid("Invalid JSON."))?;
    let android = data["app_version"].is_string() && data["timestamp"].is_string();
    if android && data["test"] == true {
        return Ok(axum::Json(json!({"status":"connected"})).into_response());
    }
    if !android
        && !["metrics", "workouts", "medications", "stateOfMind", "symptoms"].iter().any(|k| data["data"][k].is_array())
        && !data["records"].is_array()
    {
        return Err(invalid("Supply Health Auto Export JSON, Health Connect Webhook JSON or a records array."));
    }
    let batch = Sha256::digest(&raw).to_vec();
    sqlx::query!("INSERT INTO health_files(user_id,name,purpose,source,content,batch_hash) VALUES($1,'device-export.json','import','Device export',$2,$3) ON CONFLICT(user_id,batch_hash) DO NOTHING",owner,raw.as_ref(),batch).execute(&s.pool).await?;
    Ok((StatusCode::ACCEPTED, axum::Json(json!({"status":"accepted"}))).into_response())
}

pub(crate) fn seal(owner: Uuid, plaintext: &[u8]) -> Result<Vec<u8>, AppError> {
    let secret = std::env::var("HEALTH_CONNECTION_KEY").map_err(|_| {
        AppError::Status(StatusCode::SERVICE_UNAVAILABLE, "Configure the connection encryption key first.".into())
    })?;
    if secret.len() < 32 {
        return Err(invalid("Invalid connection key."));
    }
    let mut nonce = [0u8; 12];
    getrandom::fill(&mut nonce).map_err(|_| anyhow::anyhow!("randomness unavailable"))?;
    let key = LessSafeKey::new(
        UnboundKey::new(&AES_256_GCM, &Sha256::digest(secret.as_bytes()))
            .map_err(|_| invalid("Invalid encryption configuration."))?,
    );
    let mut ciphertext = plaintext.to_vec();
    key.seal_in_place_append_tag(Nonce::assume_unique_for_key(nonce), Aad::from(owner.as_bytes()), &mut ciphertext)
        .map_err(|_| invalid("Could not save connection."))?;
    Ok([nonce.as_slice(), &ciphertext].concat())
}
