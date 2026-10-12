#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/desktop"

# Check requirements
python3 -c "import PyQt6" 2>/dev/null || {
    echo "PyQt6 is required. Installing..."
    pip install PyQt6
}

echo "🚀 Launching AirBridge Desktop..."
exec python3 main.py "$@"
