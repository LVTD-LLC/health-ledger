#!/bin/sh
# Run from the repository root. Generate secrets once, without printing them.
set -eu
umask 077
set -C
if [ -e selfhost.env ]; then
    echo "selfhost.env already exists. Edit it to configure your server." >&2
    exit 1
fi
database=$(openssl rand -hex 32)
verification=$(openssl rand -hex 32)
connections=$(openssl rand -hex 32)
cat > selfhost.env <<ENV
LEDGER_DOMAIN=
MAILGUN_DOMAIN=
MAILGUN_SENDING_KEY=
LEDGER_OPERATOR_EMAIL=
MAIL_FROM=
POSTGRES_PASSWORD=$database
EMAIL_VERIFICATION_KEY=$verification
HEALTH_CONNECTION_KEY=$connections
ENV
echo "Created selfhost.env. Fill in the hostname and the three mail settings."
