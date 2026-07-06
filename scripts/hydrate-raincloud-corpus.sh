#!/usr/bin/env bash
# Hydrates the Raincloud conformance corpus (issue #205) for
# RaincloudConformanceIntegrationTest.
#
# Installs the pinned Raincloud release into a private venv, resolves each corpus
# slug via the raincloud loader (cache -> mirror -> local build), and writes the
# manifest TSV the integration test discovers:
#   <slug>\t<vortex path>\t<parquet path>
#
# Usage:
#   scripts/hydrate-raincloud-corpus.sh [--max-mb N] [slug ...]
#
# With no slugs, hydrates every entry of the conformance matrix
# (integration/src/test/resources/raincloud/expected-status.csv) whose combined
# artifact size fits --max-mb (default 200; use --max-mb 0 for no size cap — the
# full corpus is hundreds of GB and includes multi-hour builds).
#
# Per-slug failures (rotted upstream URL, missing Kaggle/HF credentials, size cap)
# skip the slug and keep going: the test only sees what hydrated. Set
# RAINCLOUD_MIRROR to turn builds into downloads.
set -euo pipefail

RAINCLOUD_TAG="v0.2.1"
CACHE_ROOT="${RAINCLOUD_HOME:-$HOME/.cache/raincloud}"
MANIFEST="${RAINCLOUD_CORPUS_MANIFEST:-$CACHE_ROOT/corpus-manifest.tsv}"
VENV="$CACHE_ROOT/conformance-venv-$RAINCLOUD_TAG"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MATRIX="$REPO_ROOT/integration/src/test/resources/raincloud/expected-status.csv"

MAX_MB=200
if [[ "${1:-}" == "--max-mb" ]]; then
    MAX_MB="$2"
    shift 2
fi

if [[ ! -d "$VENV" ]]; then
    echo "creating venv for raincloud@$RAINCLOUD_TAG"
    python3 -m venv "$VENV"
    "$VENV/bin/pip" --quiet install "raincloud[build] @ git+https://github.com/spiraldb/raincloud@$RAINCLOUD_TAG"
fi

if [[ $# -gt 0 ]]; then
    SLUGS=("$@")
else
    # not mapfile: macOS ships bash 3.2
    SLUGS=()
    while IFS= read -r slug; do
        SLUGS+=("$slug")
    done < <(grep -v '^#' "$MATRIX" | cut -d, -f1)
fi

mkdir -p "$(dirname "$MANIFEST")"
printf '%s\n' "${SLUGS[@]}" | MAX_MB="$MAX_MB" MANIFEST="$MANIFEST" "$VENV/bin/python" - <<'PY'
import json
import os
import sys
from importlib import resources

import raincloud

max_bytes = int(os.environ["MAX_MB"]) * 1024 * 1024
snapshot = json.loads(resources.files("raincloud").joinpath("_data/snapshot.json").read_text())
sizes = {
    slug: entry.get("parquet_bytes", 0) + entry.get("vortex_bytes", 0)
    for slug, entry in snapshot["slugs"].items()
}

hydrated, skipped = 0, 0
with open(os.environ["MANIFEST"], "w") as manifest:
    for slug in (line.strip() for line in sys.stdin if line.strip()):
        size = sizes.get(slug, 0)
        if max_bytes and size > max_bytes:
            print(f"skip {slug}: {size / 1e6:.0f} MB exceeds --max-mb")
            skipped += 1
            continue
        try:
            vortex = raincloud.load(slug, format="vortex").path()
            parquet = raincloud.load(slug, format="parquet").path()
        except Exception as e:  # rotted URL, missing credentials, build failure
            print(f"skip {slug}: {type(e).__name__}: {e}")
            skipped += 1
            continue
        manifest.write(f"{slug}\t{vortex}\t{parquet}\n")
        hydrated += 1
        print(f"ok   {slug}")
print(f"\nhydrated={hydrated} skipped={skipped} manifest={os.environ['MANIFEST']}")
PY
