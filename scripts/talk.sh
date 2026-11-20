#!/usr/bin/env bash
# Say something to Toffee.
#   scripts/talk.sh "add charge the tablet to my list"
set -euo pipefail
cd "$(dirname "$0")/.."
URL=$(grep '^SUPABASE_URL=' .env | cut -d= -f2-)
KEY=$(grep '^SUPABASE_PUBLISHABLE_KEY=' .env | cut -d= -f2-)
curl -s -X POST "$URL/functions/v1/turn" \
  -H "Authorization: Bearer $KEY" \
  -H "Content-Type: application/json" \
  -d "$(python -c "import json,sys;print(json.dumps({'text':sys.argv[1]}))" "$*")"
echo
