+++
title = "Self-host Health Ledger and connect Android"
slug = "android-self-hosting"
description = "Deploy your own journal, connect a phone, and configure daily Samsung Health or Health Connect sync."
section = "getting-started"
order = 2
+++

## Start your server

One server can host multiple accounts. Each person signs in to that server and chooses what to share. The phone needs an Android 14 or newer device and a reachable HTTPS address. A private instance works over a VPN when the phone can resolve the hostname and trusts its HTTPS certificate.

For a new internet-facing installation, point a domain such as health.example.org at a host with Docker Compose, and route TCP ports 80 and 443 to it. From the repository root, run:

```sh
sh scripts/init-selfhost-env.sh
```

Open the generated private `selfhost.env`. Set `LEDGER_DOMAIN` to the bare hostname. Fill in `MAILGUN_DOMAIN`, `MAILGUN_SENDING_KEY` and `MAIL_FROM` for a verified US Mailgun domain so new users can receive sign-up codes. Optionally set `LEDGER_OPERATOR_EMAIL` to the address to show on the instance’s Contact page. Keep the three generated secrets for upgrades and restores. The script refuses to overwrite the file. For optional Hevy, Withings or Google Health cloud connections, add the relevant client ID and secret listed in the repository’s `.env.example` to `selfhost.env`.

```sh
docker compose --env-file selfhost.env -f compose.selfhost.yaml up -d --build
docker compose --env-file selfhost.env -f compose.selfhost.yaml ps
```

Caddy provisions HTTPS. Postgres, uploads and certificates use named volumes. The app container runs migrations and the import worker. Back up Postgres, the storage volume and the private environment file before upgrading. Pull the desired release and rerun the same Compose command to upgrade. Keep the same Compose project and volumes.

Already running Health Ledger? Upgrade the server, keep your existing database and keys, and set `SITE_URL` to its external HTTPS origin. A custom port is supported. Serve the app at the domain root. If using an existing reverse proxy, forward the entire origin including `/.well-known/health-ledger` to Health Ledger. The separate Compose stack above is for new installations.

## Check an installation

Use the same address the phone will use. These public checks work for people, scripts and setup agents without a password:

```sh
origin=https://health.example.org
curl -fsS "$origin/healthz"
curl -fsS "$origin/.well-known/health-ledger" |
  jq -e --arg origin "$origin" '.application == "health-ledger" and .mobile_api == 1 and .site_url == $origin'
```

Open the address on the phone, create an account, and enter the emailed code. A 404 on the second check usually means an older server version or a proxy routing issue. An address mismatch means the server's configured public origin differs from the URL entered. A certificate error on Android requires a certificate chain trusted by the phone. Setup agents can also find this guide in the instance's `/llms.txt` and `/llms-full.txt`.

## Connect a phone

1. Install the Health Ledger Android app. During development, build it from the repository using JDK 17 and Android SDK 36 with `cd android && ./gradlew :app:installDebug` and an authorized USB device. A public Google Play release is pending.
2. Open **Sources → Connect your Android phone** in your server's web UI on that phone and tap **Open in Health Ledger**. Or open the Android app directly and type the server address. Check the hostname, tap **Continue to sign in**, and enter your account credentials on the sign-in screen for that server.
3. Open **Sources → Health Connect**. Grant read access to the measurements you want to share. For automatic updates grant background read access when offered, and choose **Schedule → Daily**. Under **History**, grant historical access if available. Open **Journal** or **Trends** to review imported readings after the server worker processes them.

On a Samsung phone, first open **Samsung Health → Settings → Health Connect** and allow Samsung Health to write the measurements you want. Other compatible source apps have a similar sharing setting. The native app handles scheduled uploads without a separate webhook subscription. Android controls the exact time of background jobs. Your server must be reachable when they run. After a session expires, sign in again and reconnect the source.

**More → Server address** lets you change instances. Switching signs this phone out locally and stops its scheduled transfers. Sign in and authorize sync again on the destination. Existing entries stay on their original server.

## Connect a PICOOC scale

Open **Sources → PICOOC** in the Android app. Enter your PICOOC account email and password, then tap **Connect PICOOC**. The server imports your primary profile’s weigh-ins and checks for new readings every six hours, even when the phone is offline. Open PICOOC again to check the last sync, or open **Trends** to see the measurements. Disconnecting stops future imports and keeps the entries already in your journal.

Sign-in, source setup, settings, medicines, reminders and documents use Android controls. Passkey verification and external provider authorization open in the system browser. Return to Health Ledger when prompted. Browser-assisted passkey sign-in requires the server’s default `api` feature.

## Older Samsung history

Health Connect can only return readings the source has shared with it. Granting history access can extend the query window but cannot supply missing records. For older readings, use Samsung Health's **Settings → Download personal data** and import the ZIP or a measurement CSV under **Sources → Samsung Health**. Leave Health Connect connected for future readings.

A distribution approved for the Samsung Health Data SDK can offer **Sources → Samsung Health** for a direct import of historical daily step totals followed by daily refreshes. Grant step access on the phone. The first scan checkpoints each month, and later scans revisit the last week for corrections. The phone sends days with recorded activity. The server saves each calendar day once as **Daily steps** and skips dates already covered by a step reading. Other measurement types continue through Health Connect or file import. Availability depends on the signed app and the history Samsung Health makes available on that phone.

## For Android publishers

Self-host operators and phone users need no Samsung developer account, server-side API key or webhook for either native flow. Samsung registers the Android publisher's package name and signing certificate for SDK access. The standard app builds without the proprietary SDK. Direct access for the distributed app requires Samsung partner approval for step reads and transfer to a server chosen by the user. Approval and Play publication are pending.

Apply with package `dev.lvtd.healthledger` and the SHA-256 fingerprint of the Play **app signing** certificate. Describe the permission, destination and consent flow. A fork distributed under another package or certificate needs its own registration. Keep the licensed AAR outside the repository. After approval, use the approved fingerprint and licensed SDK 1.1.0 to build the release bundle:

```sh
cd android
./gradlew :app:bundleRelease \
  -PsamsungHealthAar=/absolute/path/samsung-health-data-api-1.1.0.aar \
  -PsamsungSigningSha256="$APP_SIGNING_SHA256"
```

Use Android Studio's signed bundle workflow or sign the AAB with your upload key for Play. Test the Play-signed build with a consenting test account on an internal track before public release. Verify an old date and its step total against Samsung Health, then rerun sync and confirm it creates no duplicate. The standard release uses `./gradlew :app:bundleRelease`. [Samsung app registration](https://developer.samsung.com/health/data/process.html) and [Play App Signing](https://developer.android.com/studio/publish/app-signing).
