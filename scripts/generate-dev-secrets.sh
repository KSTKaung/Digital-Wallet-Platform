#!/usr/bin/env bash
# Print new values for the backend's required secrets as KEY=value lines, to paste into .env
# (documentations/06 MVP-ADR-23). Writes no file. Local development only; the output is secret, so
# do not commit it or paste it anywhere else.
#
# Prerequisites: Bash (Git Bash on Windows) and openssl.
#
# Formats (read by common.security.RequiredSecrets):
#   JWT_PRIVATE_KEY        RSA 2048 private key: single-line base64 of its PKCS#8 DER encoding,
#                          without PEM markers, so it fits one .env line and one Compose variable
#   JWT_KID                key id for the JWT header
#   OTP_HMAC_SECRET        base64 of 32 random bytes
#   REFRESH_TOKEN_PEPPER   base64 of 32 random bytes
#
# Usage: scripts/generate-dev-secrets.sh
set -euo pipefail

case "${1:-}" in
  "") ;;
  -h | --help) sed -n '2,15p' "$0"; exit 0 ;;
  *) echo "Unknown option: $1 (see --help)" >&2; exit 2 ;;
esac

if ! command -v openssl >/dev/null 2>&1; then
  echo "openssl not found. Install it (Git Bash on Windows includes it) and try again." >&2
  exit 1
fi

# Assigned first: a failure inside a command substitution in an echo would not stop the script.
jwt_private_key="$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null | openssl pkcs8 -topk8 -nocrypt -outform DER | openssl base64 -A)"
jwt_kid="dev-$(openssl rand -hex 8)"
otp_hmac_secret="$(openssl rand -base64 32)"
refresh_token_pepper="$(openssl rand -base64 32)"

echo "JWT_PRIVATE_KEY=${jwt_private_key}"
echo "JWT_KID=${jwt_kid}"
echo "OTP_HMAC_SECRET=${otp_hmac_secret}"
echo "REFRESH_TOKEN_PEPPER=${refresh_token_pepper}"
