#!/usr/bin/env python3
"""Generate checksums only for release deliverables, never local credentials."""
from pathlib import Path
import hashlib

root = Path(__file__).resolve().parents[1]
dist = root / "dist"
dist.mkdir(exist_ok=True)
files = sorted(p for p in dist.iterdir() if p.is_file() and (
    p.name.startswith("litehelm-linux-") or p.name in {"LiteHelm-debug.apk", "LiteHelm-release.apk", "install.sh"}
))
(dist / "SHA256SUMS").write_text("".join(
    hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.name + "\n" for p in files
))
print("Checksummed", len(files), "release files")
