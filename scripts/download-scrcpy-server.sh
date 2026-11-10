#!/usr/bin/env bash
# Downloads the scrcpy-server binary that the backend pushes to the device.
# The version must match `emulator.scrcpy.version` (SCRCPY_VERSION) exactly.
set -euo pipefail

VERSION="${SCRCPY_VERSION:-3.1}"
SHA256_3_1="958f0944a62f23b1f33a16e9eb14844c1a04b882ca175a738c16d23cb22b86c0"
DEST_DIR="$(cd "$(dirname "$0")/.." && pwd)/scrcpy-server"
DEST="$DEST_DIR/scrcpy-server"

mkdir -p "$DEST_DIR"
echo "Downloading scrcpy-server v$VERSION -> $DEST"
curl -fL --retry 3 -o "$DEST" "https://github.com/Genymobile/scrcpy/releases/download/v$VERSION/scrcpy-server-v$VERSION"

if [ "$VERSION" = "3.1" ]; then
  echo "$SHA256_3_1  $DEST" | sha256sum -c -
fi
echo "Done."
