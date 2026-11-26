-- First-party native client. The loopback port is chosen on-device for each
-- browser sign-in. Authorization codes require S256 proof of possession.
INSERT INTO oauth_clients (id, name, redirect_uris, token_endpoint_auth_method, grant_types)
VALUES ('health-ledger-android', 'Health Ledger for Android',
        ARRAY['http://127.0.0.1/android-sign-in'], 'none', ARRAY['authorization_code']);
