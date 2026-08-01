package com.lezi.babylog.domain.export
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import org.junit.Test

class ExportRecordLabelTest {
    @Test
    fun exportUsesBuiltInAuthorityAndSavedCustomSnapshot() {
        assertThat(exportRecordLabel(record(RecordType.FORMULA))).isEqualTo("配方奶")
        assertThat(
            exportRecordLabel(
                record(
                    type = RecordType.CUSTOM,
                    payloadJson =
                        """{"title":"抚触","custom_item_id":9,"icon_slot":2}""",
                ),
            ),
        ).isEqualTo("抚触")
    }

    private fun record(
        type: RecordType,
        payloadJson: String = "{}",
    ) = Record(
        id = 1,
        clientUuid = "record-${type.key}",
        babyId = 1,
        type = type,
        timestamp = 1_000L,
        payloadJson = payloadJson,
        updatedAt = 1_000L,
    )
}
