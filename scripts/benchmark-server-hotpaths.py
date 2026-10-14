#!/usr/bin/env python3
"""Compare two release lib-test executables using identical synthetic fixtures.

Both binaries must contain runtime_hotpath_timing and rate_limiter_timing.
The baseline gets the timing probes only; its runtime implementation is unchanged.
Results measure Store/server initialization and limiter calls, not HTTP throughput.
"""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import tempfile


def run(binary, test, env):
    result = subprocess.run(
        [str(binary), test, "--ignored", "--nocapture", "--test-threads=1"],
        env={**os.environ, **env}, text=True, capture_output=True, check=True,
    )
    assert "1 passed; 0 failed" in result.stdout, result.stdout
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("output", type=Path, help="new results directory")
    parser.add_argument("--rounds", type=int, default=15)
    parser.add_argument("--records", type=int, nargs="+", default=[0, 1000, 10000])
    parser.add_argument("--initialization-only", action="store_true", help="skip unchanged limiter workloads")
    args = parser.parse_args()
    if args.rounds < 1 or any(records < 0 for records in args.records):
        parser.error("rounds must be positive and record counts must be nonnegative")
    args.output.mkdir(parents=True, exist_ok=False)
    binaries = {name: path.resolve() for name, path in (("baseline", args.baseline), ("candidate", args.candidate))}
    metadata = {
        "binaries": {name: {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()} for name, path in binaries.items()},
        "rounds": args.rounds, "records": args.records, "initialization_only": args.initialization_only,
        "cpu": next((line.split(":", 1)[1].strip() for line in Path("/proc/cpuinfo").read_text().splitlines() if line.startswith("model name")), "unknown"),
    }
    (args.output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    rows = []
    with tempfile.TemporaryDirectory(prefix="lezi-hotpaths-") as temp:
        root = Path(temp)
        fixtures = {}
        for records in args.records:
            fixture = root / f"fixture-{records}"
            output = run(binaries["baseline"], "runtime_hotpath_timing", {
                "LEZI_TIMING_OPERATION": "seed", "LEZI_TIMING_DATA_DIR": str(fixture), "LEZI_TIMING_ROWS": str(records),
            })
            (args.output / f"seed-{records}.log").write_text(output)
            fixtures[records] = fixture
            metadata.setdefault("fixtures", {})[str(records)] = {
                "database_sha256": hashlib.sha256((fixture / "lezi.db").read_bytes()).hexdigest(),
                "database_bytes": (fixture / "lezi.db").stat().st_size,
            }
        (args.output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
        for round_number in range(args.rounds):
            names = list(binaries) if round_number % 2 == 0 else list(reversed(binaries))
            for name in names:
                for records, fixture in fixtures.items():
                    for operation in ["store", "startup"]:
                        trial = root / f"trial-{round_number}-{name}-{records}-{operation}"
                        shutil.copytree(fixture, trial)
                        output = run(binaries[name], "runtime_hotpath_timing", {
                            "LEZI_TIMING_OPERATION": operation, "LEZI_TIMING_DATA_DIR": str(trial),
                            "LEZI_TIMING_ITERATIONS": "41" if operation == "store" else "9",
                        })
                        (args.output / f"{round_number}-{name}-{records}-{operation}.log").write_text(output)
                        payload = json.loads(next(line.split("runtime_timing: ", 1)[1] for line in output.splitlines() if "runtime_timing: " in line))
                        for index, elapsed in enumerate(payload["samples_ns"]):
                            rows.append([round_number, name, f"{operation}_{records}", "cold_process" if index == 0 else "warm_process", index, elapsed])
                        shutil.rmtree(trial)
                if not args.initialization_only:
                    output = run(binaries[name], "rate_limiter_timing", {})
                    (args.output / f"{round_number}-{name}-rate.log").write_text(output)
                    for line in output.splitlines():
                        if "rate_limiter_timing: " in line:
                            payload = json.loads(line.split("rate_limiter_timing: ", 1)[1])
                            for index, elapsed in enumerate(payload["ns_per_call"]):
                                rows.append([round_number, name, payload["workload"], "batch_first" if index == 0 else "batch_warm", index, elapsed])
                print(f"round {round_number + 1}/{args.rounds} {name}", flush=True)
    with (args.output / "samples.csv").open("w") as out:
        writer = csv.writer(out)
        writer.writerow(["round", "variant", "workload", "phase", "sample", "ns"])
        writer.writerows(rows)
    summary = []
    for workload, phase in sorted({(row[2], row[3]) for row in rows}):
        entry = {"workload": workload, "phase": phase}
        for name in binaries:
            samples = sorted(row[5] for row in rows if row[1:4] == [name, workload, phase])
            entry[name] = {"n": len(samples), "min_ns": min(samples), "median_ns": statistics.median(samples), "p95_ns": samples[(len(samples) * 95 + 99) // 100 - 1], "max_ns": max(samples)}
        entry["median_reduction_pct"] = 100 * (1 - entry["candidate"]["median_ns"] / entry["baseline"]["median_ns"])
        paired = []
        for round_number in range(args.rounds):
            medians = {name: statistics.median(row[5] for row in rows if row[:4] == [round_number, name, workload, phase]) for name in binaries}
            paired.append(100 * (1 - medians["candidate"] / medians["baseline"]))
        entry["paired_round_reduction_pct"] = {"min": min(paired), "median": statistics.median(paired), "max": max(paired), "faster_rounds": sum(value > 0 for value in paired)}
        summary.append(entry)
    (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
