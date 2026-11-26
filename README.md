# Health Ledger

Health Ledger is an open-source, self-hosted health journal. Run the server yourself and connect the Android app to its HTTPS address. Keep a timeline of measurements, lab reports, medicines, supplements, exercise and physical or mental check-ins. Search by source, measurement or notes. Access your own records through the authenticated MCP server.

## Self-host and connect a phone

Follow [Self-host Health Ledger and connect Android](app/content/docs/android-self-hosting.md) to deploy with Docker Compose, verify the instance, and connect from the web Sources page or Android sign-in. The guide includes daily sync, older Samsung history and publisher setup.

## Run locally

Start Postgres with `docker compose up -d db`. Copy `.env.example` to `.env`, set a random `HEALTH_CONNECTION_KEY`, then run `SQLX_OFFLINE=true cargo run`. Open http://localhost:3000 and use the verification code printed by the local mail transport. The container image starts the web app and the health worker together. For native development run `python3 app/worker/worker.py` with DATABASE_URL exported and the Python dependencies from the Dockerfile installed. The worker needs Poppler and Tesseract for OCR.

## Sources

| Source | How to bring in data |
| --- | --- |
| Intervals.icu | Connect your personal API key under Sources. Backfill runs in 180-day windows back to 1970. Completed connections refresh the latest seven days every six hours. Disconnect removes the encrypted credential. |
| Samsung Health | Under **Data sources → Samsung Health**, import your downloaded ZIP or measurement CSV. For ongoing sync, allow Samsung Health to write to Health Connect and connect the native app. |
| Android Health Connect | Enable daily sync in the native app under Sources → Health Connect. This includes selected records that Samsung Health and other compatible apps have written to the phone. |
| Apple Health | Export All Health Data from your profile. Upload the ZIP or export.xml. Records retain HealthKit source names and original units. |
| Fitbit | Upload individual activity, heart rate, weight or sleep JSON arrays from an export. |
| Strava | Upload activities.csv from your account archive. |
| RENPHO | Connect your RENPHO email and password under Sources. Scale and smart tape history from your signed-in profile refreshes every six hours. See the single-session behavior below. |
| Hevy | Connect through Hevy OAuth to import workout history, exercises and sets. Edits and deletions refresh every six hours. Workout CSV uploads also work. |
| PICOOC, Google Health, OPPO OHealth, Eight Sleep, FatSecret and other sources | Import compatible dated CSV measurements or records already present in an Apple Health export. A vendor name alone does not establish a direct cloud connection. |
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

For Android, the native app syncs from Health Connect. If you already use a webhook exporter, expand **Use a separate webhook exporter** on the web Sources page for its URL and header. Historical coverage depends on what the source writes and the permissions granted. [Android setup and history](app/content/docs/android-self-hosting.md).

### RENPHO scale and tape

The direct connection uses the [unofficial RENPHO client](https://github.com/danvaneijck/renpho-api). A RENPHO login signs out the phone app. This matters for Bluetooth devices that use the phone app to upload new readings. If you need the app for each weigh-in, the scheduled Apple Health exporter may fit your workflow better.

The worker exchanges the encrypted password for a session and uses that session to read your profile’s available weight, body composition, heart rate and tape measurements. It checks the full history every six hours and updates matching readings. A failed sign-in or expired session discards the stored credential and prompts you to reconnect. Disconnect stops scheduled reads and preserves the timeline.

### Hevy workouts

Register a [Hevy OAuth app](https://developers.hevy.com/authentication) with the `read-workout` permission and callback `https://YOUR-HOST/health/cloud/hevy/callback`. Set `HEVY_CLIENT_ID` and `HEVY_CLIENT_SECRET` for the web app and worker. Users then choose **Connect Hevy** in Sources. The first import reads historical workouts page by page. Scheduled imports read updates and deletions. Workout duration, exercise sets, reps, weight, distance, duration and perceived exertion appear in the timeline when available.

### Google Health / Fitbit and Withings cloud sync

Register a web OAuth client for the [Google Health API](https://developers.google.com/health) or [Withings Public API](https://developer.withings.com/). Set the corresponding CLIENT_ID and CLIENT_SECRET from .env.example. Callback URLs are `https://YOUR-HOST/health/cloud/google-health/callback` and `https://YOUR-HOST/health/cloud/withings/callback`. The Connect button becomes available when the deployment has both values. Users authorize access at the provider. Tokens are encrypted with HEALTH_CONNECTION_KEY. Disconnect removes local credentials and stops imports. Users can also revoke access at the provider.

Google imports weight, height, body fat, resting heart rate, sleep duration, steps, distance, exercise and heart rate. Withings imports measured body composition, pulse, blood pressure and other dated scale readings. The worker checkpoints each page, then refreshes every six hours. An approved provider client and an authorizing account are required for a live sync.

The measurement history chart shows up to 90 readings for a metric and unit. Medicine and supplement courses record dose, schedule and start/end dates. Use `list_medications` with a calendar date to identify active courses. Individual doses can also be recorded as timeline entries. The JSON importer accepts FHIR Observation resources and Bundles with dated lab values, units and reference ranges. Hevy and Strong workout CSVs with exercise and rep columns import individual sets.

Samsung Health archives can be uploaded directly for body weight, heart rate, steps, blood pressure, oxygen saturation, sleep stages and exercise CSV members with supported columns. Fitbit ZIPs can contain multiple supported dated JSON files. Strava archives can contain activities.csv. The selected members in a ZIP may expand to 256 MiB total.

### Health Ledger for Android

The Kotlin app in `android/` runs on Android 14 or later. It opens on **Trends**, with daily averages, low–high ranges and reading counts for 7, 30 or 90 days and monthly charts for all time. The signed-in endpoint `/health/trends.json` groups measurements by unit and UTC calendar date. Older servers provide up to 90 recent readings per metric and the app labels partial periods. **Journal** shows the signed-in account’s entries in a native, searchable timeline. Tap a row for notes and provenance, or use **+** to add an entry. **More** opens medicines, documents, reminders and account settings. The app reads the private, session-authenticated `/health/timeline.json` endpoint and can read the existing dashboard on older servers.

**Sources → Health Connect** connects the apps that share data on the phone. New connections include permitted steps, distance, heart rate, resting heart rate, sleep sessions, exercise sessions, weight, blood pressure and oxygen saturation, retaining the writing app’s source. Existing Samsung connections keep their prior scope, schedule and checkpoints. Select **Source apps → Include all apps** to expand an existing Samsung connection. When multiple apps record the same activity, their records appear separately.

Choose **Schedule** for daily, hourly or manual updates. **History** enables older readings when permitted. **Sources** also opens cloud service setup and imports Samsung Health, Fitbit, Apple Health, Strava, CSV/TSV and FHIR files up to 32 MiB each. Imports are historical copies; disconnecting or deleting a phone reading keeps its journal entries. Reconnect after a new sign-in.

With JDK 17, Android SDK 36 and an authorized phone, build and install from `android/` with `./gradlew :app:installDebug`. Run `./gradlew :app:lintDebug :app:connectedProbeAndroidTest` for lint and device tests. The probe variant has a separate application ID and uses synthetic records.
