#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
    echo "Usage: $0 /path/to/openapi.json" >&2
    exit 2
fi

python3 -m json.tool "$1" >/dev/null
mkdir -p "$PROJECT_DIR/openapi"
cp "$1" "$PROJECT_DIR/openapi/attune.json"

echo "Updated OpenAPI contract at $PROJECT_DIR/openapi/attune.json"
