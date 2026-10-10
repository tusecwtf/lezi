#!/usr/bin/env python3
"""Exercise checked-in workflow routing without starting a build or a device.

Requires PyYAML (CI installs python3-yaml). The matcher intentionally supports
only the exact paths and directory/** patterns used here; new glob syntax must
get an explicit implementation and tests rather than silently using fnmatch's
different slash semantics.
"""

from pathlib import Path
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]
EXACT_REVISION = "${{ github.event.pull_request.head.sha || github.sha }}"


def workflow(name):
    # BaseLoader follows the spelling of GitHub's `on`, not YAML 1.1 booleans.
    return yaml.load((ROOT / ".github/workflows" / name).read_text(), Loader=yaml.BaseLoader)


def path_matches(path, pattern):
    if pattern.endswith("/**"):
        directory = pattern[:-3]
        if any(char in directory for char in "*?![]+"):
            raise ValueError(f"Unsupported workflow pattern: {pattern}")
        return path.startswith(directory + "/")
    if any(char in pattern for char in "*?![]+"):
        raise ValueError(f"Unsupported workflow pattern: {pattern}")
    return path == pattern


def triggered(config, event, changed_paths, branch="master"):
    events = config["on"]
    if event not in events:
        return False
    filters = events[event] or {}
    if "branches" in filters and branch not in filters["branches"]:
        return False
    if "paths" in filters and "paths-ignore" in filters:
        raise ValueError("GitHub forbids paths and paths-ignore on one event")
    if "paths" in filters:
        return any(path_matches(path, pattern) for path in changed_paths for pattern in filters["paths"])
    if "paths-ignore" in filters:
        return any(not any(path_matches(path, pattern) for pattern in filters["paths-ignore"])
                   for path in changed_paths)
    return True


class WorkflowContractsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.android = workflow("android-test.yml")
        cls.rust = workflow("lezi-sync.yml")
        cls.device = workflow("android-device.yml")

    def test_matcher_respects_directory_boundaries_and_mixed_changes(self):
        self.assertTrue(path_matches("config/x/nested.json", "config/**"))
        self.assertFalse(path_matches("configuration/file.json", "config/**"))
        self.assertFalse(path_matches("config", "config/**"))
        self.assertFalse(path_matches("app/build.gradle.kts.backup", "app/build.gradle.kts"))
        with self.assertRaises(ValueError):
            path_matches("a.kt", "*.kt")
        self.assertTrue(triggered(self.android, "pull_request", [".scratch/note.md", "config/new.json"]))
        self.assertTrue(triggered(self.rust, "pull_request", ["README.md", "config/new.json"]))

    def test_contract_inputs_trigger_both_consumers_and_release_fixtures(self):
        inputs = {
            "golden corpus": "config/conflict-v2-golden.json",
            "golden schema": "config/conflict-v2-golden.schema.json",
            "shared marker": "config/next-feed-plan-marker.v1.json",
            "Android release catalog": "config/android-release-compatibility.json",
            "local-data catalog": "config/local-data-contracts.json",
            "Room schema": "core/database/schemas/com.lezi.babylog.core.database.LeziDatabase/29.json",
            "server schema": "tools/lezi-sync/src/store/schema.rs",
            "closed Android codec": "sync/src/main/kotlin/com/lezi/babylog/sync/backend/HttpSyncBackend.kt",
            "shared model": "core/model/src/main/kotlin/com/lezi/babylog/core/model/Models.kt",
            "protocol contract": "docs/spec/contracts/causal-sync-wire.md",
            "update metadata": "tools/lezi-sync/deploy/app-update.json",
            "metadata validator": "app/build.gradle.kts",
            "paired launcher": "tools/testing/run-isolated-integration.sh",
        }
        for category, path in inputs.items():
            for event in ("push", "pull_request"):
                for name, config in (("Android", self.android), ("Rust/release fixtures", self.rust)):
                    with self.subTest(category=category, event=event, workflow=name):
                        self.assertTrue(triggered(config, event, [path]), path)

    def test_all_current_shared_files_and_contract_documents_are_routed(self):
        for directory in ("config", "docs/spec/contracts"):
            for file in (ROOT / directory).rglob("*"):
                if file.is_file():
                    path = file.relative_to(ROOT).as_posix()
                    for config in (self.android, self.rust):
                        with self.subTest(path=path):
                            self.assertTrue(triggered(config, "pull_request", [path]))

    def test_narrative_and_reference_changes_are_classified_separately(self):
        for event in ("push", "pull_request"):
            for path in ("README.md", "docs/testing/evidence/report.md", "docs/adr/example.md"):
                with self.subTest(event=event, path=path):
                    # Android routing is deliberately conservative; narrative-only
                    # documentation is not falsely classified as a Rust wire input.
                    self.assertTrue(triggered(self.android, event, [path]))
                    self.assertFalse(triggered(self.rust, event, [path]))
            references = [".scratch/note.md", "design/sketch.md", "prototype/example.kt"]
            self.assertFalse(triggered(self.android, event, references))
            self.assertFalse(triggered(self.rust, event, references))
            for config in (self.android, self.rust):
                self.assertFalse(triggered(config, event, ["config/new.json"], branch="unrelated"))

    def test_device_workflow_remains_explicitly_manual(self):
        self.assertEqual(set(self.device["on"]), {"workflow_dispatch"})
        for config in (self.android, self.rust):
            self.assertNotIn("pull_request_target", config["on"])
            for job in config["jobs"].values():
                commands = "\n".join(step.get("run", "") for step in job["steps"])
                self.assertNotIn("connectedDebugAndroidTest", commands)
                self.assertNotIn("workflow run", commands)

    def test_all_public_jobs_pin_and_verify_the_same_exact_commit(self):
        for config in (self.android, self.rust):
            self.assertEqual(config["env"]["LEZI_EXPECTED_REVISION"], EXACT_REVISION)
            for name, job in config["jobs"].items():
                with self.subTest(job=name, workflow=config["name"]):
                    checkout = next(step for step in job["steps"] if step.get("uses", "").startswith("actions/checkout@"))
                    self.assertEqual(checkout["with"]["ref"], EXACT_REVISION)
                    self.assertEqual(checkout["with"]["persist-credentials"], "false")
                    commands = "\n".join(step.get("run", "") for step in job["steps"])
                    self.assertIn('test "$(git rev-parse HEAD)" = "$LEZI_EXPECTED_REVISION"', commands)
                    self.assertIn("git rev-parse HEAD HEAD^{tree}", commands)
                    uploads = [step for step in job["steps"] if step.get("uses", "").startswith("actions/upload-artifact@")]
                    self.assertTrue(uploads)
                    self.assertTrue(all(step.get("if") == "always()" for step in uploads))

    def test_clean_paired_gate_cannot_be_conditional_or_fast_only(self):
        job = self.android["jobs"]["integration"]
        self.assertNotIn("if", job)
        self.assertNotIn("continue-on-error", job)
        steps = job["steps"]
        for step in steps:
            self.assertNotIn("continue-on-error", step)
            self.assertNotIn("cache", step.get("with", {}))
            self.assertNotIn("cache@", step.get("uses", ""))
        seam = next(step for step in steps if "run-isolated-integration.sh" in step.get("run", ""))
        self.assertNotIn("if", seam)
        self.assertIn("set -o pipefail", seam["run"])
        self.assertIn("tee build/ci-evidence/isolated-integration.log", seam["run"])
        tools = next(step for step in steps if "apt-get install" in step.get("run", ""))
        self.assertLess(steps.index(tools), steps.index(seam))
        for executable in ("openssl", "curl", "sqlite3"):
            self.assertIn(executable, tools["run"].split())
        self.assertTrue(any(step.get("uses", "").startswith("dtolnay/rust-toolchain@") for step in steps))

    def test_rust_format_and_each_isolated_shell_smoke_have_an_owner(self):
        steps = self.rust["jobs"]["test"]["steps"]
        commands = "\n".join(step.get("run", "") for step in steps)
        self.assertIn("cargo fmt --all -- --check", commands)
        self.assertIn("cargo test --locked", commands)
        self.assertIn("cargo clippy --all-targets --all-features -- -D warnings", commands)
        for directory in ("deploy", "docker"):
            for fixture in (ROOT / "tools/lezi-sync" / directory).glob("test-*.sh"):
                with self.subTest(fixture=fixture.name):
                    self.assertIn(f"bash {directory}/{fixture.name}", commands)
        prerequisites = next(index for index, step in enumerate(steps) if "apt-get install" in step.get("run", ""))
        first_smoke = next(index for index, step in enumerate(steps) if "bash deploy/test-" in step.get("run", ""))
        self.assertLess(prerequisites, first_smoke)
        for executable in ("openssl", "curl", "sqlite3", "ripgrep"):
            self.assertIn(executable, steps[prerequisites]["run"].split())


if __name__ == "__main__":
    unittest.main(verbosity=2)
