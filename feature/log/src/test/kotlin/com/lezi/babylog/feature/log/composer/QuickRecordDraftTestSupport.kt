package com.lezi.babylog.feature.log.composer
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.NursingPayload
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.shouldOfferNextFeedPlanForFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

internal val tappedAt = 1_721_722_800_000L

internal fun record(
    type: RecordType,
    payload: String,
) = Record(
    id = 7,
    clientUuid = "record-7",
    babyId = 1,
    type = type,
    timestamp = tappedAt,
    payloadJson = payload,
    schemaVersion = 2,
    updatedAt = tappedAt,
)

