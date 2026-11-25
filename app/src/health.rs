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
use time::OffsetDateTime;
use time::format_description::well_known::Rfc3339;
use uuid::Uuid;

const KINDS: &[&str] =
    &["Measurement", "Medicine", "Supplement", "Workout", "Check-in", "Symptom", "Lab", "Nutrition", "Sleep", "Note"];
const MAX_FILE: usize = 32 * 1024 * 1024;
pub const BODY_LIMIT: usize = MAX_FILE + 64 * 1024;
fn invalid(message: &str) -> AppError {
    AppError::Status(StatusCode::UNPROCESSABLE_ENTITY, message.into())
}
fn home() -> Response {
    Redirect::to("/dashboard").into_response()
}

#[derive(Deserialize, Default)]
pub struct Filter {
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
    let hour = sqlx::query_scalar!("SELECT hour_utc FROM health_reminders WHERE user_id=$1", user.id)
        .fetch_optional(&state.pool)
        .await?;
    let body = html! {
        section class="ledger" {
            h1 {"Health journal"}
            nav class="ledger-nav" { a href="#timeline" {"Timeline"} a href="#entry" {"Add entry"} a href="#sources" {"Sources"} a href="#files" {"Documents"} a href="/settings" {"Agent access"} }
            section id="timeline" {
                h2 {"Timeline"}
                form method="get" action="/dashboard" class="ledger-search" { input name="q" aria-label="Search records" placeholder="Search by name, source or note" value=(filter.q); button {"Search"} }
                @if rows.is_empty() { p { @if filter.q.is_empty() {"Your first entry will appear here."} @else {"No matching records."} } }
                @for row in &rows { article class="ledger-row" {
                    div { small { (txt(row,"kind")) " · " (txt(row,"source")) } h3 { (txt(row,"title")) } }
                    div { strong { (txt(row,"value")) " " (txt(row,"unit")) } p { (txt(row,"note")) } small { time datetime=(txt(row,"time")) {(txt(row,"time"))} } }
                    form method="post" action={ "/health/records/" (txt(row,"id")) "/delete" } { button {"Delete"} }
                } }
                @if filter.offset>0 { (page_link(&filter.q,(filter.offset-50).max(0),"Newer")) }
                @if rows.len()==50 { (page_link(&filter.q,filter.offset.saturating_add(50),"Older")) }
            }
            section id="entry" { h2 {"Add an entry"}
                form method="post" action="/health/records" {
                    label {"Category" select name="kind" id="record-kind" { @for k in KINDS { option { (k) } } } }
                    label {"Name" input required name="title" maxlength="160" placeholder="e.g. Weight, vitamin D or bench press"; }
                    div class="ledger-pair" { label {"Value or dose" input name="value" maxlength="160" placeholder="e.g. 72.4 or 3 × 10"; } label {"Unit" input name="unit" maxlength="64" placeholder="e.g. kg, mg or reps"; } }
                    label {"When" input type="datetime-local" id="local-time"; } input type="hidden" name="at" id="record-time";
                    label {"Notes" textarea id="record-note" name="note" maxlength="4000" rows="3" placeholder="Symptoms, mood, dosage timing or workout details" {} }
                    button type="button" hidden id="dictate" {"Dictate a note"} span role="status" id="voice-state" {}
                    button class="btn btn-primary" {"Save entry"}
                }
            }
            section id="sources" { h2 {"Sources"}
                h3 {"Intervals.icu"} p {"Connect your personal API key to import daily wellness and training activities. Recent records refresh every six hours."}
                @if let Some(status)=connection { p {(status)} form method="post" action="/health/disconnect" {button {"Disconnect"}} }
                @else {form method="post" action="/health/connect" {label {"Intervals.icu API key" input type="password" required name="token" maxlength="256" autocomplete="off";} button {"Connect"} }}
                h3 {"Import an export"} p {"Upload Apple Health export.zip or export.xml, Strava activities.csv, Fitbit data JSON, or a CSV with a date and measurements. Column headings become metric names; keep units in the headings. Use ISO dates with an offset when available."}
                (upload_form("import"))
            }
            section id="files" {h2 {"Documents"} p {"Keep the original report and extract text from PDFs, images and text files. Other file types are stored for download. Maximum 32 MiB per file."} (upload_form("document"))
                @for f in files { div class="ledger-row" { a href={ "/health/files/" (f.id) } { (f.name) } small { (f.purpose) " · " (f.status) } form method="post" action={ "/health/files/" (f.id) "/delete" } { button {"Delete file"} } } }
            }
            section { h2 {"Daily check-in reminder"} form method="post" action="/health/reminder" {label {"Email schedule" select name="hour" { option value="-1" selected[hour.is_none()] {"Off"} @for h in 0..24 {option value=(h) selected[hour==Some(h)] {(format!("{h:02}:00 UTC"))} }}} button {"Save schedule"} } }
        }
        script defer src=(state.asset_url("health.js")) {}
    };
    let page = views::Page { title: "Health journal", path: "/dashboard", user: Some(user), body };
    Ok(([(header::CACHE_CONTROL, "private, no-store")], axum::response::Html(views::layout(state, page).into_string()))
        .into_response())
}
fn txt<'a>(row: &'a Value, key: &str) -> &'a str {
    row[key].as_str().unwrap_or("")
}
fn page_link(q: &str, offset: i64, label: &str) -> Markup {
    html! { form method="get" action="/dashboard" {input type="hidden" name="q" value=(q); input type="hidden" name="offset" value=(offset);button {(label)} } }
}
fn upload_form(purpose: &str) -> Markup {
    html! { form method="post" action="/health/files" enctype="multipart/form-data" {
        input type="hidden" name="purpose" value=(purpose);
        @if purpose=="import" {label {"Source name" input name="source" required maxlength="80" placeholder="e.g. RENPHO, Samsung Health or FatSecret";}}
        label {"Choose file" input name="file" type="file" required;} button {"Upload"}
    } }
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
    Ok(home())
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
    Ok(home())
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
    let body = html! { section class="ledger" {a href="/dashboard#files" {"Documents"} h1 {(f.name)} p {(f.status)} a href={ "/health/files/" (id) "/download" } {"Download original"} pre class="ledger-text" {(f.extracted)} }};
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
    Ok(home())
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
    let secret = std::env::var("HEALTH_CONNECTION_KEY").map_err(|_| {
        AppError::Status(StatusCode::SERVICE_UNAVAILABLE, "Configure the connection encryption key first.".into())
    })?;
    let token = k.token.trim();
    if secret.len() < 32 || token.is_empty() || token.len() > 256 {
        return Err(invalid("Invalid connection key."));
    }
    let mut nonce = [0u8; 12];
    getrandom::fill(&mut nonce).map_err(|_| anyhow::anyhow!("randomness unavailable"))?;
    let key = LessSafeKey::new(
        UnboundKey::new(&AES_256_GCM, &Sha256::digest(secret.as_bytes()))
            .map_err(|_| invalid("Invalid encryption configuration."))?,
    );
    let mut ciphertext = token.as_bytes().to_vec();
    key.seal_in_place_append_tag(Nonce::assume_unique_for_key(nonce), Aad::from(u.id.as_bytes()), &mut ciphertext)
        .map_err(|_| invalid("Could not save connection."))?;
    let data = [nonce.as_slice(), &ciphertext].concat();
    sqlx::query!("INSERT INTO health_connections(user_id,credential) VALUES($1,$2) ON CONFLICT(user_id) DO UPDATE SET credential=EXCLUDED.credential,next_sync=now(),backfill_before=NULL,status='Queued'",u.id,data).execute(&s.pool).await?;
    Ok(home())
}
pub async fn disconnect(State(s): State<SharedState>, CurrentUser(u): CurrentUser) -> Result<Response, AppError> {
    sqlx::query!("DELETE FROM health_connections WHERE user_id=$1", u.id).execute(&s.pool).await?;
    Ok(home())
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
    Ok(home())
}
