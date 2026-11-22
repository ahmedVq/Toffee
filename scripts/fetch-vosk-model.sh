#!/usr/bin/env bash
# Fetches the Vosk wake-word model into the app's assets.
#
# The model is 68MB unpacked and is not in git: binaries that size belong in
# a download, not in every clone's history for ever. The build needs it
# present, so this is the one step a fresh checkout has to run before
# building.
#
# Usage: scripts/fetch-vosk-model.sh
set -euo pipefail

MODEL="vosk-model-small-en-us-0.15"
URL="https://alphacephei.com/vosk/models/${MODEL}.zip"
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/vosk-model-en-us"

if [ -f "$DEST/am/final.mdl" ]; then
  echo "model already present at $DEST"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "fetching $MODEL (about 41MB)..."
curl -fsSL -o "$TMP/model.zip" "$URL"

echo "unpacking..."
unzip -q "$TMP/model.zip" -d "$TMP"

mkdir -p "$(dirname "$DEST")"
rm -rf "$DEST"
mv "$TMP/$MODEL" "$DEST"

echo "model installed at $DEST"
