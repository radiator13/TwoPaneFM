#!/data/data/com.termux/files/usr/bin/sh
set -e
cd "$(dirname "$0")"

TOOLS_DIR=app/src/main/assets/tools
rm -rf "$TOOLS_DIR"
mkdir -p "$TOOLS_DIR"

echo "=== Native binaries ==="
cp /data/data/com.termux/files/usr/bin/zipalign "$TOOLS_DIR/"
cp /data/data/com.termux/files/usr/bin/aapt2 "$TOOLS_DIR/"
echo "$(du -sh "$TOOLS_DIR/zipalign" | cut -f1)" "zipalign"
echo "$(du -sh "$TOOLS_DIR/aapt2" | cut -f1)" "aapt2"

echo "=== Done ==="
