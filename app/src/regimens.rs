//! Medicine and supplement courses, distinct from individual dose events.
use crate::{AppError, SharedState};
use app_auth::CurrentUser;
use axum::extract::{Form, Path, State};
use axum::http::StatusCode;
use axum::response::Redirect;
use serde::Deserialize;
use serde_json::{Value, json};
use sqlx::PgPool;
use time::Date;
use time::macros::format_description;
use uuid::Uuid;

pub fn date(input: &str) -> Result<Date, AppError> {
    Date::parse(input, format_description!("[year]-[month]-[day]"))
        .map_err(|_| AppError::Status(StatusCode::UNPROCESSABLE_ENTITY, "Enter a calendar date.".into()))
}

pub async fn list(pool: &PgPool, owner: Uuid, on: Option<Date>) -> Result<Vec<Value>, sqlx::Error> {
    let rows=sqlx::query!("SELECT id,name,dose,schedule,starts,ends FROM health_regimens WHERE user_id=$1 AND ($2::date IS NULL OR (starts <= $2 AND (ends IS NULL OR ends >= $2))) ORDER BY starts DESC,id",owner,on).fetch_all(pool).await?;
    Ok(rows.into_iter().map(|r|json!({"id":r.id,"name":r.name,"dose":r.dose,"schedule":r.schedule,"start":r.starts.to_string(),"end":r.ends.map(|d|d.to_string())})).collect())
}
#[derive(Deserialize)]
pub struct Course {
    name: String,
    dose: String,
    schedule: String,
    starts: String,
    ends: String,
}
pub async fn add(
    State(s): State<SharedState>,
    CurrentUser(u): CurrentUser,
    Form(f): Form<Course>,
) -> Result<Redirect, AppError> {
    let starts = date(&f.starts)?;
    let ends = if f.ends.is_empty() { None } else { Some(date(&f.ends)?) };
    if ends.is_some_and(|d| d < starts)
        || f.name.trim().is_empty()
        || f.name.len() > 640
        || f.schedule.len() > 1000
        || f.dose.len() > 640
    {
        return Err(AppError::Status(
            StatusCode::UNPROCESSABLE_ENTITY,
            "Check the medicine name, dose, schedule and date range.".into(),
        ));
    }
    sqlx::query!(
        "INSERT INTO health_regimens(user_id,name,dose,schedule,starts,ends) VALUES($1,$2,$3,$4,$5,$6)",
        u.id,
        f.name.trim(),
        f.dose,
        f.schedule,
        starts,
        ends
    )
    .execute(&s.pool)
    .await?;
    Ok(Redirect::to("/dashboard#medicines"))
}
#[derive(Deserialize)]
pub struct End {
    ends: String,
}
pub async fn end(
    State(s): State<SharedState>,
    CurrentUser(u): CurrentUser,
    Path(id): Path<Uuid>,
    Form(f): Form<End>,
) -> Result<Redirect, AppError> {
    let at = date(&f.ends)?;
    let change =
        sqlx::query!("UPDATE health_regimens SET ends=$1 WHERE id=$2 AND user_id=$3 AND starts<=$1", at, id, u.id)
            .execute(&s.pool)
            .await?;
    if change.rows_affected() == 0 {
        return Err(AppError::Status(StatusCode::UNPROCESSABLE_ENTITY, "Check the course and its start date.".into()));
    }
    Ok(Redirect::to("/dashboard#medicines"))
}
pub async fn delete(
    State(s): State<SharedState>,
    CurrentUser(u): CurrentUser,
    Path(id): Path<Uuid>,
) -> Result<Redirect, AppError> {
    sqlx::query!("DELETE FROM health_regimens WHERE id=$1 AND user_id=$2", id, u.id).execute(&s.pool).await?;
    Ok(Redirect::to("/dashboard#medicines"))
}
