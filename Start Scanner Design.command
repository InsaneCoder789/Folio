#!/bin/zsh
set -eu
SCANNER_PROJECT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
exec /usr/bin/env python3 "$SCANNER_PROJECT_DIR/tools/serve_design.py" --open
