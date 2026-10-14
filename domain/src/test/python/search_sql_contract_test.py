"""Execute production DAO SQL against SQLite without Android or a copied SQL predicate.

This verifies candidate narrowing and documents the old duration omission. It does
not execute Kotlin/Room generation or replace CareLogSearchRoomDeviceTest.
Run: python3 domain/src/test/python/search_sql_contract_test.py
"""

from pathlib import Path
import sqlite3
import json
import math
import unittest


ROOT = Path(__file__).resolve().parents[4]


def query_before(path, declaration):
    source = (ROOT / path).read_text()
    end = source.index(declaration)
    closing = source.rfind('"""', 0, end)
    opening = source.rfind('"""', 0, closing)
    return source[opening + 3 : closing].strip()


DAO_PATH = "core/database/src/main/kotlin/com/lezi/babylog/core/database/TimelineWindowDao.kt"
PEER_SQL = query_before(DAO_PATH, "suspend fun listOpenSleepCandidateRoots(")
EXACT_ROOT_SQL = query_before(DAO_PATH, "suspend fun listSearchSleepRoots(")
EXACT_WAKE_SQL = query_before(DAO_PATH, "suspend fun listSearchSleepWakes(")
DETAIL_ROOT_SQL = query_before(DAO_PATH, "suspend fun listRecordRootsByClientUuids(")


def bind_roots(sql, roots, baby=1):
    assert 0 < len(roots) <= 64
    names = [f"root_{i}" for i in range(len(roots))]
    return sql.replace(":rootClientUuids", ",".join(":" + name for name in names)), {
        "babyId": baby, **dict(zip(names, roots))
    }


SEARCH_SQL = query_before(
    "core/database/src/main/kotlin/com/lezi/babylog/core/database/Daos.kt",
    "suspend fun searchCandidates(",
)


class SearchSqlContractTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.row_factory = sqlite3.Row
        schema = json.loads((ROOT / "core/database/schemas/com.lezi.babylog.core.database.LeziDatabase/29.json").read_text())
        for entity in schema["database"]["entities"]:
            self.db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
            for index in entity["indices"]:
                self.db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))

    def seed(self, uuid, *, baby=1, deleted=None, record_type="sleep", note=None):
        self.db.execute(
            "INSERT INTO records (clientUuid,babyId,deletedAt,timestamp,note,payloadJson,type,schemaVersion,updatedAt) VALUES (?, ?, ?, 1700000000000, ?, ?, ?, 2, 1700000000000)",
            (uuid, baby, deleted, note, '{"is_nap":false,"anomaly_flag":false}', record_type),
        )

    def candidates(self, query, type_key="sleep"):
        pattern = "%" + "".join("\\" + c if c in "\\%_" else c for c in query) + "%"
        return self.db.execute(
            SEARCH_SQL,
            {"babyId": 1, "escapedPattern": pattern, "matchingTypeKeys": type_key},
        ).fetchall()

    def test_compact_duration_needs_sleep_candidates_not_payload_substrings(self):
        self.seed("sleep")
        self.assertEqual([], self.candidates("30m", "__no_matching_record_type__"))
        self.assertEqual(["sleep"], [row["clientUuid"] for row in self.candidates("30m")])
        # Baseline first filters the raw ongoing summary, then projects it closed.
        self.assertEqual(["sleep"], [row["clientUuid"] for row in self.candidates("进行中")])
        self.assertNotIn("进行中", "时长 30m")

    def test_duration_candidates_keep_baby_deletion_and_adoption_boundaries(self):
        self.seed("visible")
        self.seed("deleted", deleted=1)
        self.seed("other", baby=2)
        self.seed("unadopted")
        self.db.execute(
            "INSERT INTO fulfillment_candidates (clientUuid,carePlanClientUuid,recordClientUuid,confirmedAt,adoptionStatus,updatedAt) VALUES ('candidate','plan','unadopted',1,'conflict_not_adopted',1)"
        )
        self.assertEqual(["visible"], [row["clientUuid"] for row in self.candidates("30m")])

    def test_duration_narrows_to_sleep_rows_without_loading_unrelated_history(self):
        for index in range(129):
            self.seed(f"sleep-{index}")
        for index in range(1000):
            self.seed(f"bath-{index}", record_type="bath")
            self.seed(f"other-{index}", baby=2)
        self.assertEqual(129, len(self.candidates("30m")))
        plan = self.db.execute(
            "EXPLAIN QUERY PLAN " + SEARCH_SQL,
            {"babyId": 1, "escapedPattern": "%30m%", "matchingTypeKeys": "sleep"},
        ).fetchall()
        self.assertTrue(any("SEARCH records USING INDEX" in row[3] and "babyId=?" in row[3] for row in plan))

    def test_literal_like_metacharacters_are_not_wildcards(self):
        self.seed("literal", record_type="bath", note="100%")
        self.seed("plain", record_type="bath", note="100ml")
        self.assertEqual(["literal"], [row["clientUuid"] for row in self.candidates("100%", "__none__")])

    def seed_modern_history(self, count):
        for i in range(count):
            self.seed(f"sleep-{i}")
            wake = self.db.execute(
                "INSERT INTO wake_observations (clientUuid,sleepRecordClientUuid,wakeTimestamp,updatedAt) VALUES (?,?,1700001800000,1)",
                (f"wake-{i}", f"sleep-{i}"),
            ).lastrowid
            self.db.execute(
                "INSERT INTO media_assets (clientUuid,kind,wakeObservationId,localUri,createdAt) VALUES (?,'wake',?,'test.jpg',1)",
                (f"media-{i}", wake),
            )
            # Many irrelevant conflicts exercise the correlated lookup, not a
            # repeated complete adoption-status scan for each root batch.
            self.db.execute(
                "INSERT INTO fulfillment_candidates (clientUuid,carePlanClientUuid,recordClientUuid,confirmedAt,adoptionStatus,updatedAt) VALUES (?, 'plan', ?, 1, 'conflict_not_adopted', 1)",
                (f"conflict-{i}", f"irrelevant-{i}"),
            )

    def exact_projection_reads(self, requested):
        peers = self.db.execute(PEER_SQL, {"babyId": 1}).fetchall()
        context = {row["clientUuid"]: row for row in peers}
        stats = {"peer_rows": len(peers), "root_rows": 0, "wake_rows": 0, "queries": 1}
        missing = [uuid for uuid in requested if uuid not in context]
        for offset in range(0, len(missing), 64):
            sql, args = bind_roots(EXACT_ROOT_SQL, missing[offset:offset + 64])
            rows = self.db.execute(sql, args).fetchall()
            context.update((row["clientUuid"], row) for row in rows)
            stats["root_rows"] += len(rows)
            stats["queries"] += 1
        roots = list(context)
        for offset in range(0, len(roots), 64):
            sql, args = bind_roots(EXACT_WAKE_SQL, roots[offset:offset + 64])
            stats["wake_rows"] += len(self.db.execute(sql, args).fetchall())
            stats["queries"] += 1
        return stats

    def test_modern_history_materializes_one_peer_context_not_one_per_batch(self):
        for count in (129, 1024):
            with self.subTest(count=count):
                self.db.execute("DELETE FROM records")
                self.db.execute("DELETE FROM wake_observations")
                self.db.execute("DELETE FROM media_assets")
                self.db.execute("DELETE FROM fulfillment_candidates")
                self.seed_modern_history(count)
                roots = [f"sleep-{i}" for i in range(count)]
                old_rows = 0
                for offset in range(0, count, 64):
                    sql, args = bind_roots(DETAIL_ROOT_SQL, roots[offset:offset + 64])
                    old_rows += len(self.db.execute(sql, args).fetchall())
                stats = self.exact_projection_reads(roots)
                self.assertEqual(count * math.ceil(count / 64), old_rows)
                self.assertEqual(count, stats["peer_rows"])
                self.assertEqual(0, stats["root_rows"])
                self.assertEqual(count, stats["wake_rows"])
                self.assertEqual(1 + math.ceil(count / 64), stats["queries"])
                print(f"modern N={count}: old repeated root rows={old_rows}, new={stats}")

    def test_exact_queries_use_uuid_and_correlated_adoption_indexes(self):
        self.seed_modern_history(129)
        for name, statement in (("roots", EXACT_ROOT_SQL), ("wakes", EXACT_WAKE_SQL)):
            sql, args = bind_roots(statement, [f"sleep-{i}" for i in range(64)])
            plan = [row[3] for row in self.db.execute("EXPLAIN QUERY PLAN " + sql, args)]
            self.assertTrue(any("SEARCH r USING INDEX index_records_clientUuid (clientUuid=?)" in step for step in plan), plan)
            self.assertTrue(any("SEARCH f USING INDEX index_fulfillment_candidates_recordClientUuid (recordClientUuid=?)" in step for step in plan), plan)
            if name == "wakes":
                self.assertTrue(any("SEARCH w USING INDEX index_wake_observations_sleepRecordClientUuid" in step for step in plan), plan)
            self.assertFalse(any(step.startswith("SCAN ") for step in plan), plan)
            print(f"{name} query plan: {plan}")

    def test_exact_wake_vm_work_scales_with_rows_not_square_of_history(self):
        counts = (1024, 2048, 4096)
        steps = []
        for count in counts:
            self.db.execute("DELETE FROM records")
            self.db.execute("DELETE FROM wake_observations")
            self.db.execute("DELETE FROM media_assets")
            self.db.execute("DELETE FROM fulfillment_candidates")
            self.seed_modern_history(count)
            roots = [f"sleep-{i}" for i in range(count)]
            ticks = [0]
            def progress():
                ticks[0] += 1
                return 0
            self.db.set_progress_handler(progress, 100)
            returned = 0
            for offset in range(0, count, 64):
                sql, args = bind_roots(EXACT_WAKE_SQL, roots[offset:offset + 64])
                returned += len(self.db.execute(sql, args).fetchall())
            self.db.set_progress_handler(None, 0)
            self.assertEqual(count, returned)
            steps.append(ticks[0] * 100)
        # Structural budget, not a wall-clock or device-performance claim.
        self.assertLess(steps[1], steps[0] * 2.5)
        self.assertLess(steps[2], steps[1] * 2.5)
        print(f"exact wake SQLite VM op estimates: {dict(zip(counts, steps))}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
