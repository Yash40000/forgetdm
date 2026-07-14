#!/usr/bin/env sh
set -eu

: "${FORGETDM_URL:?Set FORGETDM_URL}"
: "${FORGETDM_TOKEN:?Set FORGETDM_TOKEN}"
: "${FORGETDM_TEMPLATE_ID:?Set FORGETDM_TEMPLATE_ID}"
PURPOSE="${FORGETDM_PURPOSE:-CI pipeline test-data request}"
ENVIRONMENT="${FORGETDM_ENVIRONMENT:-QA}"

AUTH="Authorization: Bearer ${FORGETDM_TOKEN}"
BODY=$(printf '{"templateId":"%s","purpose":"%s","environment":"%s"}' "$FORGETDM_TEMPLATE_ID" "$PURPOSE" "$ENVIRONMENT")
RESPONSE=$(curl -fsS -H "$AUTH" -H 'Content-Type: application/json' -d "$BODY" "$FORGETDM_URL/api/self-service/requests")
REQUEST_ID=$(printf '%s' "$RESPONSE" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
[ -n "$REQUEST_ID" ] || { echo "Could not parse request id" >&2; exit 1; }
echo "ForgeTDM request $REQUEST_ID submitted"

i=0
while [ "$i" -lt 240 ]; do
  RESPONSE=$(curl -fsS -H "$AUTH" "$FORGETDM_URL/api/self-service/requests/$REQUEST_ID")
  STATUS=$(printf '%s' "$RESPONSE" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p')
  case "$STATUS" in
    APPROVED) curl -fsS -H "$AUTH" -H 'Content-Type: application/json' -d '{}' "$FORGETDM_URL/api/self-service/requests/$REQUEST_ID/fulfill"; exit 0 ;;
    FULFILLED) printf '%s\n' "$RESPONSE"; exit 0 ;;
    REJECTED) printf '%s\n' "$RESPONSE" >&2; exit 2 ;;
  esac
  sleep 15
  i=$((i + 1))
done
echo "Timed out waiting for ForgeTDM approval" >&2
exit 3
