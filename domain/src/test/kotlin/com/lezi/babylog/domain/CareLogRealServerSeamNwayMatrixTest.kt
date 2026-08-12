package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.ConflictResolveOutcome
import com.lezi.babylog.sync.conflict.ConflictOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/**
 * H32 acceptance: N-way field merge, same-field 2/3-way conflict, and set(null)
 * are independent of arrival/enumeration order on the primary CareLog→real-server seam.
 *
 * Requires a current lezi-sync binary (shared cargo target-dir, tools/lezi-sync/target,
 * or LEZI_SYNC_BIN). Never touches family NAS paths or production certificates.
 */
class CareLogRealServerSeamNwayMatrixTest {

    @Test
    fun nwayFieldNullMatrixIndependentOfArrivalOrder() = runBlocking {
        CareLogRealServerSeamFixture.open().use { fixture ->
            val ownerB = fixture.joinExtraOwner("owner-b")
            val ownerC = fixture.joinExtraOwner("owner-c")
            val writers = listOf(fixture.owner, ownerB, ownerC)
            val evidence = mutableListOf<CaseEvidence>()

            evidence += runDifferentLeaves(fixture, writers)
            evidence += runSameLeafTwoValues(fixture, writers)
            evidence += runSameLeafThreeValues(fixture, writers)
            evidence += runTwoSameOneUnchanged(fixture, writers)
            evidence += runConcreteVsNull(fixture, writers)
            evidence += runAncestorPayloadVsDescendant(fixture, writers)

            assertThat(evidence).hasSize(6)
            evidence.forEach { row ->
                assertThat(row.digests.distinct()).hasSize(1)
                assertThat(row.orders).isNotEmpty()
            }
        }
    }

    /** C1: A edits /note, B edits /payload_json/amount_ml → auto-merge both orders. */
    private suspend fun runDifferentLeaves(
        fixture: CareLogRealServerSeamFixture,
        writers: List<SeamClient>,
    ): CaseEvidence {
        val a = writers[0]
        val b = writers[1]
        val settleOrders = listOf(listOf(a, b), listOf(b, a))
        val digests = mutableListOf<String>()
        for ((index, order) in settleOrders.withIndex()) {
            val seeded = seedFormulaRecord(
                fixture, writers, "base-diff-leaves", 90, 100,
                "22222222-2222-4222-8222-22222222221$index",
            )
            a.careLog.updateRecord(
                id = requireNotNull(a.recordByClientUuid(seeded.uuid)).id,
                timestamp = seeded.timestamp,
                endTimestamp = null,
                note = "note-from-a",
                payloadJson = formulaPayloadJson(amountMl = 90, preparedMl = 100),
                nowMillis = a.clock.nowMillis(),
            )
            b.careLog.updateRecord(
                id = requireNotNull(b.recordByClientUuid(seeded.uuid)).id,
                timestamp = seeded.timestamp,
                endTimestamp = null,
                note = "base-diff-leaves",
                payloadJson = formulaPayloadJson(amountMl = 150, preparedMl = 100),
                nowMillis = b.clock.nowMillis(),
            )
            order.forEach { it.settleLocalWrite() }
            fixture.pullAll(writers + fixture.member)

            assertThat(writers.mapNotNull { it.openConflictIdForRecord(seeded.uuid) }).isEmpty()
            val fact = a.stableRecordFact(seeded.uuid)
            assertThat(fact.note).isEqualTo("note-from-a")
            assertThat(fact.payloadJson).contains("\"amount_ml\":150")
            assertThat(fact.openConflictId).isNull()
            assertThat(fact.syncDirty).isFalse()
            assertThat(fact.baseVersion).isNotEqualTo(seeded.baseVersion)
            (writers + fixture.member).forEach { client ->
                assertThat(client.stableRecordFact(seeded.uuid)).isEqualTo(fact)
            }
            digests += "auto|note=${fact.note}|payload=${fact.payloadJson}"
        }
        assertThat(digests.distinct()).hasSize(1)
        return CaseEvidence(
            caseId = "C1-different-leaves-auto-merge",
            orders = settleOrders.map { it.joinToString("→") { c -> c.label } },
            digests = digests,
            kind = "auto_merge",
        )
    }

    /** C2: A/B same /note two values → conflict; forward+reverse. */
    private suspend fun runSameLeafTwoValues(
        fixture: CareLogRealServerSeamFixture,
        writers: List<SeamClient>,
    ): CaseEvidence {
        val a = writers[0]
        val b = writers[1]
        val orders = listOf(listOf(a, b), listOf(b, a))
        val digests = mutableListOf<String>()
        val notes = mapOf(a to "note-alpha", b to "note-beta")
        for ((index, order) in orders.withIndex()) {
            val seeded = seedFormulaRecord(
                fixture, writers, "base-same-leaf", 90, null,
                "22222222-2222-4222-8222-22222222222$index",
            )
            order.forEach { client ->
                client.careLog.updateRecord(
                    id = requireNotNull(client.recordByClientUuid(seeded.uuid)).id,
                    timestamp = seeded.timestamp,
                    endTimestamp = null,
                    note = notes.getValue(client),
                    payloadJson = formulaPayloadJson(90),
                    nowMillis = client.clock.nowMillis(),
                )
            }
            order.forEach { it.settleLocalWrite() }
            fixture.pullAll(writers + fixture.member)

            val conflictId = requireSingleOpenConflict(writers, seeded.uuid)
            val snapshot = a.loadConflict(conflictId).snapshot
            assertAutoConflictDisjoint(snapshot)
            val notePath = snapshot.conflicting.single { it.path == "/note" }
            assertThat(notePath.candidates).hasSize(2)
            val values = notePath.candidates
                .map { it.outcome }
                .filterIsInstance<ConflictOutcome.Set>()
                .map { it.value }
                .toSet()
            assertThat(values).containsExactly(
                JsonPrimitive("note-alpha"),
                JsonPrimitive("note-beta"),
            )
            digests += semanticConflictDigest(snapshot)

            val (_, outcome) = a.resolveOpenConflict(
                conflictId = conflictId,
                preferValueByPath = mapOf("/note" to JsonPrimitive("note-alpha")),
            )
            assertThat(outcome).isInstanceOf(ConflictResolveOutcome.Accepted::class.java)
            fixture.pullAll(writers + fixture.member)
            val resolved = a.stableRecordFact(seeded.uuid)
            assertThat(resolved.note).isEqualTo("note-alpha")
            assertThat(resolved.openConflictId).isNull()
            assertThat(resolved.syncDirty).isFalse()
            (writers + fixture.member).forEach {
                assertThat(it.stableRecordFact(seeded.uuid)).isEqualTo(resolved)
            }
        }
        assertThat(digests.distinct()).hasSize(1)
        return CaseEvidence(
            caseId = "C2-same-leaf-two-values-conflict",
            orders = orders.map { it.joinToString("→") { c -> c.label } },
            digests = digests,
            kind = "conflict",
        )
    }

    /** C3: A/B/C three distinct /note values; three cyclic arrival orders. */
    private suspend fun runSameLeafThreeValues(
        fixture: CareLogRealServerSeamFixture,
        writers: List<SeamClient>,
    ): CaseEvidence {
        val (a, b, c) = writers
        val cyclic = listOf(
            listOf(a, b, c),
            listOf(b, c, a),
            listOf(c, a, b),
        )
        val digests = mutableListOf<String>()
        val notes = mapOf(a to "note-a", b to "note-b", c to "note-c")
        for ((index, order) in cyclic.withIndex()) {
            val seeded = seedFormulaRecord(
                fixture, writers, "base-three", 90, null,
                "22222222-2222-4222-8222-22222222223$index",
            )
            order.forEach { client ->
                client.careLog.updateRecord(
                    id = requireNotNull(client.recordByClientUuid(seeded.uuid)).id,
                    timestamp = seeded.timestamp,
                    endTimestamp = null,
                    note = notes.getValue(client),
                    payloadJson = formulaPayloadJson(90),
                    nowMillis = client.clock.nowMillis(),
                )
            }
            order.forEach { it.settleLocalWrite() }
            fixture.pullAll(writers + fixture.member)

            val conflictId = requireSingleOpenConflict(writers, seeded.uuid)
            val snapshot = a.loadConflict(conflictId).snapshot
            assertAutoConflictDisjoint(snapshot)
            assertThat(snapshot.conflicting.single { it.path == "/note" }.candidates).hasSize(3)
            digests += semanticConflictDigest(snapshot)

            val (_, outcome) = a.resolveOpenConflict(
                conflictId = conflictId,
                preferValueByPath = mapOf("/note" to JsonPrimitive("note-b")),
            )
            assertThat(outcome).isInstanceOf(ConflictResolveOutcome.Accepted::class.java)
            fixture.pullAll(writers + fixture.member)
            val resolved = a.stableRecordFact(seeded.uuid)
            assertThat(resolved.note).isEqualTo("note-b")
            assertThat(resolved.openConflictId).isNull()
            (writers + fixture.member).forEach {
                assertThat(it.stableRecordFact(seeded.uuid)).isEqualTo(resolved)
            }
        }
        assertThat(digests.distinct()).hasSize(1)
        return CaseEvidence(
            caseId = "C3-same-leaf-three-values-conflict",
            orders = cyclic.map { it.joinToString("→") { client -> client.label } },
            digests = digests,
            kind = "conflict",
        )
    }

    /** C4: two changers agree on /note; third unchanged → auto-merge. */
    private suspend fun runTwoSameOneUnchanged(
        fixture: CareLogRealServerSeamFixture,
        writers: List<SeamClient>,
    ): CaseEvidence {
        val (a, b, c) = writers
        val orders = listOf(listOf(a, b), listOf(b, a))
        val digests = mutableListOf<String>()
        for ((index, order) in orders.withIndex()) {
            val seeded = seedFormulaRecord(
                fixture, writers, "base-agree", 90, null,
                "22222222-2222-4222-8222-22222222224$index",
            )
            order.forEach { client ->
                client.careLog.updateRecord(
                    id = requireNotNull(client.recordByClientUuid(seeded.uuid)).id,
                    timestamp = seeded.timestamp,
                    endTimestamp = null,
                    note = "agreed-note",
                    payloadJson = formulaPayloadJson(90),
                    nowMillis = client.clock.nowMillis(),
                )
            }
            order.forEach { it.settleLocalWrite() }
            fixture.pullAll(writers + fixture.member)

            assertThat(writers.mapNotNull { it.openConflictIdForRecord(seeded.uuid) }).isEmpty()
            val fact = a.stableRecordFact(seeded.uuid)
            assertThat(fact.note).isEqualTo("agreed-note")
            assertThat(fact.openConflictId).isNull()
            assertThat(c.stableRecordFact(seeded.uuid)).isEqualTo(fact)
            digests += "auto|note=${fact.note}|payload=${fact.payloadJson}"
        }
        assertThat(digests.distinct()).hasSize(1)
        return CaseEvidence(
            caseId = "C4-two-same-one-unchanged-auto-merge",
            orders = orders.map {
                it.joinToString("→") { client -> client.label } + "+unchanged:${c.label}"
            },
            digests = digests,
            kind = "auto_merge",
        )
    }

    /** C5: concrete note vs set(null) → conflict both arrival orders. */
    private suspend fun runConcreteVsNull(
        fixture: CareLogRealServerSeamFixture,
        writers: List<SeamClient>,
    ): CaseEvidence {
        val a = writers[0]
        val b = writers[1]
        val orders = listOf(listOf(a, b), listOf(b, a))
        val digests = mutableListOf<String>()
        for ((index, order) in orders.withIndex()) {
            val seeded = seedFormulaRecord(
                fixture, writers, "has-note", 90, null,
                "22222222-2222-4222-8222-22222222225$index",
            )
            a.careLog.updateRecord(
                id = requireNotNull(a.recordByClientUuid(seeded.uuid)).id,
                timestamp = seeded.timestamp,
                endTimestamp = null,
                note = "concrete-note",
                payloadJson = formulaPayloadJson(90),
                nowMillis = a.clock.nowMillis(),
            )
            b.careLog.updateRecord(
                id = requireNotNull(b.recordByClientUuid(seeded.uuid)).id,
                timestamp = seeded.timestamp,
                endTimestamp = null,
                note = null,
                payloadJson = formulaPayloadJson(90),
                nowMillis = b.clock.nowMillis(),
            )
            order.forEach { it.settleLocalWrite() }
            fixture.pullAll(writers + fixture.member)

            val conflictId = requireSingleOpenConflict(writers, seeded.uuid)
            val snapshot = a.loadConflict(conflictId).snapshot
            assertAutoConflictDisjoint(snapshot)
            val outcomes = snapshot.conflicting.single { it.path == "/note" }.candidates
                .map { it.outcome }
                .toSet()
            assertThat(outcomes).contains(ConflictOutcome.Set(JsonPrimitive("concrete-note")))
            assertThat(outcomes).contains(ConflictOutcome.Set(JsonNull))
            digests += semanticConflictDigest(snapshot)

            val (_, outcome) = a.resolveOpenConflict(
                conflictId = conflictId,
                preferValueByPath = mapOf("/note" to JsonNull),
            )
            assertThat(outcome).isInstanceOf(ConflictResolveOutcome.Accepted::class.java)
            fixture.pullAll(writers + fixture.member)
            val resolved = a.stableRecordFact(seeded.uuid)
            assertThat(resolved.note).isNull()
            assertThat(resolved.openConflictId).isNull()
            (writers + fixture.member).forEach {
                assertThat(it.stableRecordFact(seeded.uuid)).isEqualTo(resolved)
            }
        }
        assertThat(digests.distinct()).hasSize(1)
        return CaseEvidence(
            caseId = "C5-concrete-vs-null-conflict",
            orders = orders.map { it.joinToString("→") { c -> c.label } },
            digests = digests,
            kind = "conflict",
        )
    }

    /**
     * C6: ancestor payload rewrite (drop prepared_ml → whole /payload_json set in the
     * snapshot classifier) vs descendant amount_ml edit. Commit-time leaf merge would
     * auto-combine pure payload leaves, so each head also writes a distinct /note to
     * force open branches; the matrix still proves path-prefix normalization keeps
     * /payload_json conflicting (no silent leaf winner) across arrival orders.
     */
    private suspend fun runAncestorPayloadVsDescendant(
        fixture: CareLogRealServerSeamFixture,
        writers: List<SeamClient>,
    ): CaseEvidence {
        val a = writers[0]
        val b = writers[1]
        val orders = listOf(listOf(a, b), listOf(b, a))
        val digests = mutableListOf<String>()
        for ((index, order) in orders.withIndex()) {
            val seeded = seedFormulaRecord(
                fixture, writers, "base-ancestor", 90, 100,
                "22222222-2222-4222-8222-22222222226$index",
            )
            a.careLog.updateRecord(
                id = requireNotNull(a.recordByClientUuid(seeded.uuid)).id,
                timestamp = seeded.timestamp,
                endTimestamp = null,
                note = "ancestor-head",
                payloadJson = formulaPayloadJson(amountMl = 90),
                nowMillis = a.clock.nowMillis(),
            )
            b.careLog.updateRecord(
                id = requireNotNull(b.recordByClientUuid(seeded.uuid)).id,
                timestamp = seeded.timestamp,
                endTimestamp = null,
                note = "descendant-head",
                payloadJson = formulaPayloadJson(amountMl = 150, preparedMl = 100),
                nowMillis = b.clock.nowMillis(),
            )
            order.forEach { it.settleLocalWrite() }
            fixture.pullAll(writers + fixture.member)

            val conflictId = requireSingleOpenConflict(writers, seeded.uuid)
            val snapshot = a.loadConflict(conflictId).snapshot
            assertAutoConflictDisjoint(snapshot)
            assertThat(snapshot.conflicting.map { it.path }).containsAtLeast(
                "/note",
                "/payload_json",
            )
            assertThat(snapshot.conflicting.none { it.path.startsWith("/payload_json/") }).isTrue()
            assertThat(snapshot.autoMerged.none { it.path.startsWith("/payload_json/") }).isTrue()
            digests += semanticConflictDigest(snapshot)

            val (_, outcome) = a.resolveOpenConflict(
                conflictId = conflictId,
                preferValueByPath = mapOf("/note" to JsonPrimitive("ancestor-head")),
            )
            assertThat(outcome).isInstanceOf(ConflictResolveOutcome.Accepted::class.java)
            fixture.pullAll(writers + fixture.member)
            val resolved = a.stableRecordFact(seeded.uuid)
            assertThat(resolved.openConflictId).isNull()
            assertThat(resolved.syncDirty).isFalse()
            assertThat(resolved.note).isEqualTo("ancestor-head")
            (writers + fixture.member).forEach {
                assertThat(it.stableRecordFact(seeded.uuid)).isEqualTo(resolved)
            }
        }
        assertThat(digests.distinct()).hasSize(1)
        return CaseEvidence(
            caseId = "C6-ancestor-payload-vs-descendant-conflict",
            orders = orders.map { it.joinToString("→") { c -> c.label } },
            digests = digests,
            kind = "conflict",
        )
    }

    private data class SeededRecord(
        val uuid: String,
        val baseVersion: String,
        val timestamp: Long,
    )

    private suspend fun seedFormulaRecord(
        fixture: CareLogRealServerSeamFixture,
        writers: List<SeamClient>,
        note: String,
        amountMl: Int,
        preparedMl: Int?,
        clientUuid: String,
    ): SeededRecord {
        val owner = fixture.owner
        val baby = owner.fakes.babies.listAll().firstOrNull()
            ?: run {
                val babyId = owner.careLog.createBaby(
                    CreateBabyInput(
                        nickname = "H32-Lele",
                        birthdayEpochDay = 20_000L,
                        sex = "female",
                    ),
                )
                owner.settleLocalWrite()
                requireNotNull(owner.fakes.babies.get(babyId))
            }
        fixture.pullAll(writers + fixture.member)
        val localBabyId = requireNotNull(
            owner.fakes.babies.listAll().firstOrNull { it.clientUuid == baby.clientUuid }?.id,
        )
        owner.clock.now = owner.clock.nowMillis() + 1_000L
        val ts = owner.clock.nowMillis() - 30_000L
        owner.careLog.addRecord(
            babyId = localBabyId,
            type = RecordType.FORMULA,
            timestamp = ts,
            note = note,
            payloadJson = formulaPayloadJson(amountMl = amountMl, preparedMl = preparedMl),
            nowMillis = owner.clock.nowMillis(),
            clientUuid = clientUuid,
        )
        owner.settleLocalWrite()
        val settled = requireNotNull(owner.recordByClientUuid(clientUuid))
        assertThat(settled.syncDirty).isFalse()
        val baseVersion = requireNotNull(settled.baseVersion)
        fixture.pullAll(writers + fixture.member)
        writers.forEach { client ->
            val row = requireNotNull(client.recordByClientUuid(clientUuid))
            assertThat(row.baseVersion).isEqualTo(baseVersion)
            assertThat(row.syncDirty).isFalse()
        }
        return SeededRecord(clientUuid, baseVersion, ts)
    }

    private suspend fun requireSingleOpenConflict(
        writers: List<SeamClient>,
        recordUuid: String,
    ): String {
        val ids = writers.mapNotNull { it.openConflictIdForRecord(recordUuid) }.distinct()
        check(ids.size == 1) {
            "expected one open conflict for $recordUuid, got $ids " +
                writers.map { it.label to it.openConflictIdForRecord(recordUuid) }
        }
        return ids.single()
    }

    private data class CaseEvidence(
        val caseId: String,
        val orders: List<String>,
        val digests: List<String>,
        val kind: String,
    )
}
