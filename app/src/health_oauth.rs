//! Outbound, read-only provider OAuth for user-authorized health imports.
use crate::{AppError, SharedState};
use app_auth::CurrentUser;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect, Response};
use serde::Deserialize;
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::time::Duration;

pub struct Provider {
    pub id: &'static str,
    pub title: &'static str,
    prefix: &'static str,
    authorize: &'static str,
    token: &'static str,
    scope: &'static str,
}
pub const PROVIDERS: &[Provider] = &[
    Provider {
        id: "hevy",
        title: "Hevy",
        prefix: "HEVY",
        authorize: "https://hevy.com/oauth/authorize",
        token: "https://hevy.com/api/token",
        scope: "",
    },
    Provider {
        id: "google-health",
        title: "Google Health / Fitbit",
        prefix: "GOOGLE_HEALTH",
        authorize: "https://accounts.google.com/o/oauth2/v2/auth",
        token: "https://oauth2.googleapis.com/token",
        scope: "https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly https://www.googleapis.com/auth/googlehealth.sleep.readonly",
    },
    Provider {
        id: "withings",
        title: "Withings",
        prefix: "WITHINGS",
        authorize: "https://account.withings.com/oauth2_user/authorize2",
        token: "https://wbsapi.withings.net/v2/oauth2",
        scope: "user.metrics",
    },
];
impl Provider {
    fn credentials(&self) -> Option<(String, String)> {
        let id = std::env::var(format!("{}_CLIENT_ID", self.prefix)).ok()?;
        let secret = std::env::var(format!("{}_CLIENT_SECRET", self.prefix)).ok()?;
        (!id.trim().is_empty() && !secret.trim().is_empty()).then_some((id, secret))
    }
    pub fn configured(&self) -> bool {
        self.credentials().is_some()
    }
}
fn provider(id: &str) -> Result<&'static Provider, AppError> {
    PROVIDERS
        .iter()
        .find(|p| p.id == id)
        .ok_or_else(|| AppError::Status(StatusCode::NOT_FOUND, "Unknown provider".into()))
}
fn unavailable() -> AppError {
    AppError::Status(StatusCode::SERVICE_UNAVAILABLE, "Provider registration required.".into())
}
fn denied() -> AppError {
    AppError::Status(
        StatusCode::BAD_REQUEST,
        "Connection expired or declined. Start a new connection in Sources.".into(),
    )
}
fn redirect_url(s: &SharedState, id: &str) -> String {
    format!("{}/health/cloud/{id}/callback", s.cfg.site_url.trim_end_matches('/'))
}

pub async fn start(
    State(s): State<SharedState>,
    CurrentUser(u): CurrentUser,
    Path(id): Path<String>,
    headers: axum::http::HeaderMap,
) -> Result<Redirect, AppError> {
    let p = provider(&id)?;
    let (client, _) = p.credentials().ok_or_else(unavailable)?;
    let mut random = [0u8; 32];
    getrandom::fill(&mut random)?;
    let state = crate::routes::provider_state(&headers, hex::encode(random));
    let digest = Sha256::digest(state.as_bytes()).to_vec();
    sqlx::query!("INSERT INTO health_oauth_attempts(user_id,provider,state_hash,expires) VALUES ($1,$2,$3,now()+interval '10 minutes') ON CONFLICT(user_id,provider) DO UPDATE SET state_hash=EXCLUDED.state_hash,expires=EXCLUDED.expires",u.id,id,digest).execute(&s.pool).await?;
    let mut url = reqwest::Url::parse(p.authorize)?;
    url.query_pairs_mut()
        .append_pair("client_id", &client)
        .append_pair("state", &state)
        .append_pair("redirect_uri", &redirect_url(&s, &id));
    if !p.scope.is_empty() {
        url.query_pairs_mut().append_pair("scope", p.scope).append_pair("response_type", "code");
    }
    if id == "google-health" {
        url.query_pairs_mut().append_pair("access_type", "offline").append_pair("prompt", "consent");
    }
    Ok(Redirect::to(url.as_str()))
}
#[derive(Deserialize)]
pub struct Callback {
    state: Option<String>,
    code: Option<String>,
}
pub async fn callback(
    State(s): State<SharedState>,
    CurrentUser(u): CurrentUser,
    Path(id): Path<String>,
    Query(q): Query<Callback>,
) -> Result<Redirect, AppError> {
    let p = provider(&id)?;
    let (client, secret) = p.credentials().ok_or_else(unavailable)?;
    let state = q.state.ok_or_else(denied)?;
    let digest = Sha256::digest(state.as_bytes()).to_vec();
    let used = sqlx::query!(
        "DELETE FROM health_oauth_attempts WHERE user_id=$1 AND provider=$2 AND state_hash=$3 AND expires>now()",
        u.id,
        id,
        digest
    )
    .execute(&s.pool)
    .await?;
    if used.rows_affected() != 1 {
        return Err(denied());
    }
    let code = q.code.ok_or_else(denied)?;
    let uri = redirect_url(&s, &id);
    let mut fields = vec![
        ("code", code.as_str()),
        ("grant_type", "authorization_code"),
        ("client_id", &client),
        ("client_secret", &secret),
    ];
    if id != "hevy" {
        fields.push(("redirect_uri", &uri));
    }
    if id == "withings" {
        fields.push(("action", "requesttoken"));
    }
    let response = reqwest::Client::builder()
        .timeout(Duration::from_secs(10))
        .redirect(reqwest::redirect::Policy::none())
        .build()?
        .post(p.token)
        .form(&fields)
        .send()
        .await?;
    if !response.status().is_success() {
        return Err(denied());
    }
    let response: Value = response.json().await?;
    let data = if id == "withings" { &response["body"] } else { &response };
    if data["refresh_token"].as_str().is_none() || data["access_token"].as_str().is_none() {
        return Err(denied());
    }
    let mut tokens = data.clone();
    if let Some(seconds) = data["expires_in"].as_i64() {
        tokens["expires_at"] =
            serde_json::json!(time::OffsetDateTime::now_utc().unix_timestamp().saturating_add(seconds));
    }
    save_connection(&s, u.id, &id, &tokens).await
}

#[derive(Deserialize)]
pub struct ScaleLogin {
    email: String,
    password: String,
}
pub async fn connect_renpho(
    State(s): State<SharedState>,
    CurrentUser(u): CurrentUser,
    axum::Form(input): axum::Form<ScaleLogin>,
) -> Result<Response, AppError> {
    connect_scale(&s, &u, "renpho", input).await
}

pub async fn connect_picooc(
    State(s): State<SharedState>,
    CurrentUser(u): CurrentUser,
    axum::Form(input): axum::Form<ScaleLogin>,
) -> Result<Response, AppError> {
    connect_scale(&s, &u, "picooc", input).await
}

async fn connect_scale(
    s: &SharedState,
    user: &app_auth::User,
    provider: &str,
    input: ScaleLogin,
) -> Result<Response, AppError> {
    let email = input.email.trim();
    if !email.contains('@') || email.len() > 320 || input.password.is_empty() || input.password.len() > 1024 {
        let page = crate::health::sources(s, user, Some((provider, email))).await?;
        return Ok((StatusCode::UNPROCESSABLE_ENTITY, page).into_response());
    }
    Ok(save_connection(s, user.id, provider, &serde_json::json!({"email":email, "password":input.password}))
        .await?
        .into_response())
}

async fn save_connection(s: &SharedState, owner: uuid::Uuid, id: &str, data: &Value) -> Result<Redirect, AppError> {
    let encrypted = crate::health::seal(owner, &serde_json::to_vec(data)?)?;
    sqlx::query!("INSERT INTO health_cloud_sources(user_id,provider,credential) VALUES($1,$2,$3) ON CONFLICT(user_id,provider) DO UPDATE SET credential=EXCLUDED.credential,cursor_json='{}',next_sync=now(),status='Queued'",owner,id,encrypted).execute(&s.pool).await?;
    Ok(Redirect::to("/sources"))
}
pub async fn disconnect(
    State(s): State<SharedState>,
    CurrentUser(u): CurrentUser,
    Path(id): Path<String>,
) -> Result<Redirect, AppError> {
    let mut tx = s.pool.begin().await?;
    sqlx::query!("DELETE FROM health_oauth_attempts WHERE user_id=$1 AND provider=$2", u.id, id)
        .execute(&mut *tx)
        .await?;
    sqlx::query!("DELETE FROM health_cloud_sources WHERE user_id=$1 AND provider=$2", u.id, id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(Redirect::to("/sources"))
}
