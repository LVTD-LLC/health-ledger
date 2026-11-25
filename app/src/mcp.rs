//! The Model Context Protocol (MCP) server, mounted at `/mcp` when the
//! `mcp` feature is enabled. It lets coding agents (Claude Code, Cursor and
//! anything else speaking MCP's Streamable HTTP transport) call your product
//! as tools.
//!
//! The protocol and transport live in `app_core::mcp`: stateless
//! JSON-RPC over `POST`, version negotiation, error codes and the result
//! helpers. This module only describes the tools. Requests authenticate
//! with the same personal API key as the JSON API ([`ApiUser`]), so the
//! `mcp` feature depends on `api`; browser sessions are never accepted.
//!
//! One tool ships with every project, `get_account`, the MCP twin of
//! `GET /api/v1/me`. Add your product's tools in [`tools`] (the schema the
//! model sees) and [`call`] (what runs). Keep business logic in your own
//! modules and call in from here, as the API handlers do; report failures
//! the model can fix with [`tool_error`] so it can retry with better input.

use crate::SharedState;
use crate::api::{self, ApiError, ApiUser};
use app_auth::User;
use app_core::mcp::{Server, Tools, tool_error, tool_result};
use axum::body::Bytes;
use axum::extract::State;
use axum::http::HeaderMap;
use axum::response::Response;
use serde_json::{Value, json};
use std::borrow::Cow;
use std::sync::LazyLock;

pub const PATH: &str = "/mcp";

/// What `initialize` tells a client. Built once; the title is the product name.
static SERVER: LazyLock<Server> = LazyLock::new(|| Server {
    name: env!("CARGO_PKG_NAME"),
    title: crate::views::SITE_NAME,
    version: env!("CARGO_PKG_VERSION"),
    instructions: Cow::Owned(format!(
        "{} exposes its product operations as MCP tools. Every tool runs as the account that owns the API key \
         this connection uses. Call get_account first to see which account that is.",
        crate::views::SITE_NAME
    )),
});

/// `POST /mcp`. The key is checked by the extractor before any JSON-RPC is read.
pub async fn handle(
    State(state): State<SharedState>,
    ApiUser(user): ApiUser,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    app_core::mcp::handle(&SERVER, &ProductTools { state: &state, user: &user }, &headers, &body).await
}

struct ProductTools<'a> {
    state: &'a SharedState,
    user: &'a User,
}

impl Tools for ProductTools<'_> {
    fn list(&self) -> Vec<Value> {
        tools()
    }

    async fn call(&self, name: &str, arguments: Value) -> anyhow::Result<Option<Value>> {
        Ok(Some(match call(self.state, self.user, name, arguments).await {
            Ok(Some(result)) => result,
            Ok(None) => return Ok(None),
            Err(ApiError::Status(status, message)) => tool_error(status, &message),
            Err(ApiError::Internal(error)) => return Err(error),
        }))
    }
}

/// The `tools/list` document: one entry per tool with the JSON Schema of
/// its arguments. Describe each tool for a model, not a programmer: say
/// what it returns and when to use it.
pub fn tools() -> Vec<Value> {
    vec![
        json!({
            "name": "get_account",
            "title": "Account",
            "description": "The account behind this API key: id, email, name, admin flag and join date. Call it first to confirm which account the tools act as.",
            "inputSchema": { "type": "object", "properties": {}, "additionalProperties": false },
            "annotations": { "readOnlyHint": true, "destructiveHint": false, "idempotentHint": true, "openWorldHint": false },
        }),
        json!({
            "name":"search_health_records","description":"Search your measurements, medicines, workouts and check-ins. Returns up to 50 records with timestamps, units and source names. Request subsequent pages with offset.",
            "inputSchema":{"type":"object","properties":{"query":{"type":"string","maxLength":200},"offset":{"type":"integer","minimum":0}},"additionalProperties":false},
            "annotations":{"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}
        }),
        json!({
            "name":"search_documents","description":"Find your documents by filename or extracted text. Returns document IDs, filenames, extraction status and a short text preview.",
            "inputSchema":{"type":"object","properties":{"query":{"type":"string","maxLength":200},"offset":{"type":"integer","minimum":0}},"additionalProperties":false},
            "annotations":{"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}
        }),
        json!({
            "name":"read_document","description":"Read a passage of your extracted document text by ID and character offset. OCR may contain transcription errors; use the original to verify numbers.",
            "inputSchema":{"type":"object","properties":{"id":{"type":"string","format":"uuid"},"offset":{"type":"integer","minimum":0}},"required":["id"],"additionalProperties":false},
            "annotations":{"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}
        }),
    ]
}

/// Run one tool. `Ok(None)` means the name is unknown; the protocol layer
/// turns that into an "unknown tool" error that lists the known names.
async fn call(state: &SharedState, user: &User, name: &str, arguments: Value) -> Result<Option<Value>, ApiError> {
    let query = arguments["query"].as_str().unwrap_or("");
    let offset = arguments["offset"].as_i64().unwrap_or(0);
    if query.chars().count() > 200 || !(0..=100_000_000).contains(&offset) {
        return Err(ApiError::status(axum::http::StatusCode::BAD_REQUEST, "Invalid search or offset."));
    }
    Ok(Some(match name {
        "get_account" => {
            let account = api::account_json(state, user).await?;
            let who = match account["name"].as_str() {
                Some(name) if !name.is_empty() => format!("{name} <{}>", user.email),
                _ => user.email.clone(),
            };
            tool_result(format!("This key belongs to {who}."), account)
        }
        "search_health_records" => {
            let rows = crate::health::records(&state.pool, user.id, query, offset).await?;
            tool_result(serde_json::to_string(&rows).unwrap_or_default(), json!({"records":rows}))
        }
        "search_documents" => {
            let rows=sqlx::query!("SELECT id,name,status,left(extracted,300) AS preview FROM health_files WHERE user_id=$1 AND purpose='document' AND strpos(lower(name || ' ' || extracted),lower($2))>0 ORDER BY created_at DESC,id DESC LIMIT 25 OFFSET $3",user.id,query,offset).fetch_all(&state.pool).await?;
            let docs: Vec<_> =
                rows.iter().map(|r| json!({"id":r.id,"name":r.name,"status":r.status,"preview":r.preview})).collect();
            tool_result(serde_json::to_string(&docs).unwrap_or_default(), json!({"documents":docs}))
        }
        "read_document" => {
            let id = arguments["id"]
                .as_str()
                .and_then(|id| uuid::Uuid::parse_str(id).ok())
                .ok_or_else(|| ApiError::status(axum::http::StatusCode::BAD_REQUEST, "Supply a document ID."))?;
            let row=sqlx::query!("SELECT name,substring(extracted FROM $3 FOR 8000) AS passage,length(extracted) AS total FROM health_files WHERE user_id=$1 AND id=$2 AND purpose='document'",user.id,id,offset as i32+1).fetch_optional(&state.pool).await?.ok_or_else(||ApiError::status(axum::http::StatusCode::NOT_FOUND,"Document not found."))?;
            tool_result(
                row.passage.clone().unwrap_or_default(),
                json!({"name":row.name,"text":row.passage,"characters":row.total}),
            )
        }
        _ => return Ok(None),
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_tool_is_described_for_a_model() {
        let tools = tools();
        assert_eq!(tools[0]["name"], "get_account");
        for tool in &tools {
            assert!(tool["description"].as_str().unwrap().len() > 40, "{}", tool["name"]);
            assert_eq!(tool["inputSchema"]["type"], "object");
            assert!(tool["annotations"]["readOnlyHint"].is_boolean(), "{}", tool["name"]);
        }
        assert!(SERVER.instructions.contains("get_account"));
        assert_eq!(SERVER.title, crate::views::SITE_NAME);
    }
}
