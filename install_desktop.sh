#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DESKTOP_FILE="$SCRIPT_DIR/desktop/airbridge.desktop"
TARGET_DIR="$HOME/.local/share/applications"

mkdir -p "$TARGET_DIR"
cp "$DESKTOP_FILE" "$TARGET_DIR/airbridge.desktop"
chmod +x "$TARGET_DIR/airbridge.desktop"
update-desktop-database "$TARGET_DIR" 2>/dev/null || true

echo "✅ AirBridge desktop launcher installed to $TARGET_DIR/airbridge.desktop"
