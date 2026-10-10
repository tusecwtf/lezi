#!/usr/bin/env python3
"""Check startup-control exclusion in existing R8 DEX/mapping, without packaging an APK."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile
from startup_dex_tables import read_dex_identities


CLASSES = (
    "StartupBoundaryObservation",
    "LocalDataInspectionControl",
    "ProductionStartupTestRunner",
    "ProductionStartupSchedule",
    "ProductionStartupReadinessDeviceTest",
    "ProductionAcceptanceEntryPoint",
    "RendererStartupProbeService",
    "AppStartupObservation",
)
MARKERS = (
    "beforeInspect",
    "leziStartupGateAcceptance",
    "gate:stale-terminal-rejected",
    "sync:activate",
    "sync:recovery-start",
    "sync:session-collect",
    "session:collect",
    "room:construct",
    "room:open",
    "network:available",
    "network:business-request",
    "network:setup-request",
    "application:persistent-start",
    "application:foreground",
    "application:background",
    "application:before-hilt",
    "application:after-hilt",
    "application:renderer-guard-return",
    "business:local-data-gate",
    "business:foreground-state",
    "business:lifecycle-observer",
    "business:installer-recovery",
    "business:installer-recovery-complete",
    "business:export-cache-cleanup",
    "business:export-cache-cleanup-complete",
    "business:reminder-cleanup",
    "business:reminder-rehydrate",
    "business:widget-start",
    "business:foreground-sync",
)


def inspect_payloads(payloads: dict[str, bytes], mapping: Path, usage: Path | None = None) -> dict:
    failures = []
    identities = {
        name: read_dex_identities(payload) for name, payload in payloads.items()
        if name.endswith(".dex")
    }
    if not identities:
        raise ValueError("At least one valid DEX is required")
    for name, payload in payloads.items():
        for marker in CLASSES + MARKERS:
            if any(marker.encode(encoding) in payload for encoding in ("utf-8", "utf-16le")):
                failures.append(f"{name}: retained {marker}")
    removed_classes = set(usage.read_text().splitlines()) if usage is not None else set()
    removed_evidence = []
    current_class = None
    sentinel_block = None
    saw_class = False
    member_pattern = re.compile(
        r"(?:(?:[0-9]+):(?:[0-9]+):)?\S+ \S+\([^)]*\)(?::[0-9]+(?::[0-9]+)?)? -> \S+"
        r"|\S+ \S+ -> \S+",
    )
    with mapping.open() as lines:
        for raw in lines:
            line = raw.rstrip("\r\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            entry = re.fullmatch(r"(\S+) -> (\S+):", line)
            if entry is None:
                if current_class is None or not line.startswith("    ") or "\t" in line or \
                        member_pattern.fullmatch(line[4:]) is None:
                    raise ValueError("Malformed/unrecognized R8 mapping line")
                if sentinel_block:
                    failures.append(f"mapping: removed sentinel has member entries: {sentinel_block}")
                continue
            original, target = entry.groups()
            current_class = original
            sentinel_block = None
            saw_class = True
            if not original.startswith("com.lezi.") or not any(name in original for name in CLASSES):
                continue
            # R8 writes source-file-only entries for pruned inline holders. This is
            # not ordinary obfuscation: accept only corroborated complete absence.
            # Authority: MappedPositionToClassNameMapperBuilder.addSourceFileLinesForPrunedClasses.
            if re.fullmatch(r"R8\$\$REMOVED\$\$CLASS\$\$[0-9]+", target) is None:
                failures.append(f"mapping: retained/renamed diagnostic class {original} -> {target}")
                continue
            sentinel_block = original
            if original not in removed_classes:
                failures.append(f"usage: no whole-class removal evidence for {original}")
                continue
            descriptors = {"L" + name.replace(".", "/") + ";" for name in (original, target)}
            found = []
            for dex_name, tables in identities.items():
                for table_name, values in tables.items():
                    # An array reference to the class also disproves complete absence.
                    if any(value.lstrip("[") in descriptors for value in values):
                        found.append(f"{dex_name}:{table_name}")
            if found:
                failures.append(f"DEX: removed mapping still referenced: {original}: {found}")
                continue
            removed_evidence.append({"original": original, "sentinel": target,
                                     "usage_whole_class_removed": True,
                                     "absent_from_all_dex_identity_tables": True})
    if not saw_class:
        raise ValueError("R8 mapping must contain class entries")
    return {
        "mapping": str(mapping),
        "mapping_sha256": hashlib.sha256(mapping.read_bytes()).hexdigest(),
        "usage": str(usage) if usage is not None else None,
        "usage_sha256": hashlib.sha256(usage.read_bytes()).hexdigest() if usage is not None else None,
        "dex_identity_counts": {
            name: {table: len(values) for table, values in tables.items()}
            for name, tables in identities.items()
        },
        "corroborated_removed_mapping_entries": removed_evidence,
        "payload_sha256": {
            name: hashlib.sha256(payload).hexdigest() for name, payload in payloads.items()
        },
        "checked_classes": list(CLASSES),
        "checked_markers": list(MARKERS),
        "failures": failures,
        "status": "failed" if failures else "passed",
        "scope": "R8 DEX/manifest/mapping exclusion only; no device execution or signing attestation",
    }


def verify(apk: Path, mapping: Path, usage: Path | None = None) -> dict:
    """Optional inspection of an independently existing APK; never build/package one for this."""
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate ZIP entry names are not an unambiguous artifact")
        entries = [name for name in names
                   if re.fullmatch(r"classes(?:\d+)?\.dex", name)]
        if not entries or "AndroidManifest.xml" not in names:
            raise ValueError("Input must be a complete application APK with DEX and manifest")
        payloads = {name: archive.read(name) for name in entries + ["AndroidManifest.xml"]}
    return inspect_payloads(payloads, mapping, usage) | {
        "apk": str(apk),
        "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    }


def verify_r8(dex_directory: Path, manifest: Path, mapping: Path, usage: Path | None = None) -> dict:
    entries = sorted(dex_directory.glob("*.dex"))
    if any(re.fullmatch(r"classes(?:\d+)?\.dex", path.name) is None for path in entries):
        raise ValueError("Unexpected DEX filename in R8 output; do not silently omit it")
    if not entries:
        raise ValueError("No classes*.dex found in the selected R8 output directory")
    payloads = {str(path): path.read_bytes() for path in entries}
    payloads[str(manifest)] = manifest.read_bytes()
    return inspect_payloads(payloads, mapping, usage) | {"dex_directory": str(dex_directory)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--apk", type=Path)
    source.add_argument("--dex-directory", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--usage", type=Path, required=True,
                        help="Matching R8 usage.txt; required for pruned-class corroboration")
    args = parser.parse_args()
    if args.dex_directory:
        if not args.manifest:
            parser.error("--manifest is required with --dex-directory")
        report = verify_r8(args.dex_directory, args.manifest, args.mapping, args.usage)
    else:
        report = verify(args.apk, args.mapping, args.usage)
    print(json.dumps(report, indent=2))
    return bool(report["failures"])


if __name__ == "__main__":
    raise SystemExit(main())
