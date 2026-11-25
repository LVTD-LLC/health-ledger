# Health Ledger

An open-source personal health dashboard built with ShipRust, Rust and PostgreSQL. Keep a timeline of measurements, lab reports, medicines, supplements, exercise and physical or mental check-ins. Search by source, measurement or notes. Access your own records through the authenticated MCP server.

## Run locally

Start Postgres with `docker compose up -d db`. Copy `.env.example` to `.env`, set a random `HEALTH_CONNECTION_KEY`, then run `SQLX_OFFLINE=true cargo run`. Open http://localhost:3000 and use the verification code printed by the local mail transport. The container image starts the web app and the health worker together. For native development run `python3 app/worker/worker.py` with DATABASE_URL exported and the Python dependencies from the Dockerfile installed. The worker needs Poppler and Tesseract for OCR.

## Sources

| Source | How to bring in data |
| --- | --- |
| Intervals.icu | Connect your personal API key under Sources. Backfill runs in 180-day windows back to 1970. Completed connections refresh the latest seven days every six hours. Disconnect removes the encrypted credential. |
| Apple Health | Export All Health Data from your profile. Upload the ZIP or export.xml. Records retain HealthKit source names and original units. |
| Fitbit | Upload individual activity, heart rate, weight or sleep JSON arrays from an export. |
| Strava | Upload activities.csv from your account archive. |
| RENPHO, PICOOC, Samsung Health, Google Health, OPPO OHealth, Eight Sleep, FatSecret and other sources | Import compatible dated CSV measurements or records already present in an Apple Health export. A vendor name alone does not establish a direct cloud connection. |
| Other devices or custom exports | Use a UTF-8 CSV with timestamp,metric,value,unit,kind,note columns, or a wide CSV with one numeric column per measurement. Include units in the column headings, e.g. Weight (kg). |

Timestamps with offsets preserve the instant. Dates and times without an offset are interpreted as UTC. Repeat imports using the same source label to deduplicate. A 32 MiB upload limit applies; Apple ZIPs may expand to 256 MiB. Deleting an import file preserves the imported records. Delete records in the timeline or delete the account to remove everything.

## Documents and voice

Upload an original report or any other document. PDF text extraction covers the first 50 pages. Image-only PDFs fall back to English OCR for the first ten pages. PNG, TIFF and JPEG also support OCR; other binary formats remain downloadable. Text is capped at 150,000 characters per file. Compare numbers against the original before making decisions.

Browser dictation transcribes into an editable draft. Your browser may use its speech provider. Review the category, dose and transcript before saving. Use manual entry where speech recognition is unavailable. Daily check-in emails are opt-in, scheduled in UTC and contain a link to the journal.

## MCP

Connect to `https://YOUR-HOST/mcp` using OAuth consent or a personal API key created in Settings. The read-only health tools are `search_health_records`, `search_documents` and `read_document`. Record searches return up to 50 entries, document searches up to 25, and document reads up to 8,000 characters per request. Page through results with offset. Access is limited to the signed-in account. An authorized assistant receives the returned health data. Revoke a client or key in Settings.

## Deployment

GitHub Actions checks the selected features on main, builds a revision-tagged GHCR image, deploys using a CapRover app token, and verifies the revision from /healthz. Set APP_TOKEN and CAPROVER_SERVER as repository secrets and PRODUCTION_URL as a repository variable. Configure persistent Postgres, SITE_URL, the Mailgun verification settings and HEALTH_CONNECTION_KEY at runtime. Retain the connection key across deploys and back up the database, which includes the original documents. See [generated operational notes](docs/shiprust-operations.md) for account and operator commands.

[MIT license](LICENSE.md). Generated with ShipRust and released under MIT for this repository by LVTD LLC.

### Scheduled device exports

In Sources, create a write-only exporter key. For Apple Health, set up [Health Auto Export's REST automation](https://help.healthyapps.dev/en/health-auto-export/automations/rest-api/) with the displayed POST URL and Authorization header. Select JSON, turn on batching and pick a schedule. Use the historical export option to send older readings. Supported arrays include metrics and workouts. Other exporters can POST the following structure to the same endpoint. Split batches at 8 MiB and reuse a stable source and ID across retries.

```json
{"records":[{"id":"scale-123","source":"Home scale","timestamp":"2026-01-02T08:30:00+01:00","kind":"Measurement","metric":"Weight","value":70.5,"unit":"kg"}]}
```

### Google Health / Fitbit and Withings cloud sync

Register a web OAuth client for the [Google Health API](https://developers.google.com/health) or [Withings Public API](https://developer.withings.com/). Set the corresponding CLIENT_ID and CLIENT_SECRET from .env.example. Callback URLs are `https://YOUR-HOST/health/cloud/google-health/callback` and `https://YOUR-HOST/health/cloud/withings/callback`. The Connect button becomes available when the deployment has both values. Users authorize access at the provider. Tokens are encrypted with HEALTH_CONNECTION_KEY. Disconnect removes local credentials and stops imports. Users can also revoke access at the provider.

Google imports weight, height, body fat, resting heart rate, sleep duration, steps, distance, exercise and heart rate. Withings imports measured body composition, pulse, blood pressure and other dated scale readings. The worker checkpoints each page, then refreshes every six hours. An approved provider client and an authorizing account are required for a live sync.

The measurement history chart shows up to 90 readings for a metric and unit. Medicine and supplement courses record dose, schedule and start/end dates. Use `list_medications` with a calendar date to identify active courses. Individual doses can also be recorded as timeline entries. The JSON importer accepts FHIR Observation resources and Bundles with dated lab values, units and reference ranges. Hevy and Strong workout CSVs with exercise and rep columns import individual sets.
