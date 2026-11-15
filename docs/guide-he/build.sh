#!/usr/bin/env bash
# Renders the Hebrew guide (guide.html, right-to-left) to docs/GateDelay_Installation_Guide_HE.pdf with headless Chrome/Edge.
set -euo pipefail
cd "$(dirname "$0")"
for b in "/c/Program Files/Google/Chrome/Application/chrome.exe" "/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe" google-chrome chromium; do
  if command -v "$b" >/dev/null 2>&1 || [ -x "$b" ]; then BROWSER="$b"; break; fi
done
OUT="$(cd .. && pwd -W 2>/dev/null || pwd)/GateDelay_Installation_Guide_HE.pdf"
SRC="file:///$(pwd -W 2>/dev/null || pwd)/guide.html"
"$BROWSER" --headless=new --disable-gpu --no-pdf-header-footer --print-to-pdf="$OUT" "$SRC" 2>/dev/null
echo "Wrote $OUT"
