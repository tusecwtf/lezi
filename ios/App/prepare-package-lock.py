#!/usr/bin/env python3
"""Seed Xcode's workspace lock from the authoritative ios/Package.resolved.

Xcode's app graph has a different originHash from the standalone package graph.
Use the supported version-2 lock format (same exact pins, no originHash) rather
than carrying the package graph's version-3 originHash into this workspace.
No dependency resolution or network access occurs in this script.
"""
import json
from pathlib import Path

app = Path(__file__).resolve().parent
source = app.parent / "Package.resolved"
target = app / "LeziDevelopment.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved"
resolved = json.loads(source.read_text())
if resolved.get("version") not in (2, 3) or not resolved.get("pins"):
    raise SystemExit("Unsupported or empty authoritative package lock; refusing to seed Xcode")
for pin in resolved["pins"]:
    if not pin.get("state", {}).get("revision"):
        raise SystemExit("A dependency has no pinned revision; refusing to seed Xcode")
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps({"pins": resolved["pins"], "version": 2}, indent=2) + "\n")
print("Seeded Xcode dependency pins from ios/Package.resolved")
