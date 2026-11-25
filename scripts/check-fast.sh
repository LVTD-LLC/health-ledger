#!/usr/bin/env bash
# Feedback on the selected features, using the checked-in query metadata.
set -euo pipefail
cd "$(dirname "$0")/.."
cargo fmt --all --check
node --test app/tests/*.test.cjs
SQLX_OFFLINE=true cargo clippy --locked --workspace --all-targets -- -D warnings
