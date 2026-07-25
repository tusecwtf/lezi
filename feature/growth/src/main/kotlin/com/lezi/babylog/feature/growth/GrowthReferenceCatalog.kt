package com.lezi.babylog.feature.growth

import android.content.Context
import com.lezi.babylog.core.model.GrowthReferenceBand
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.Sex
import com.lezi.babylog.domain.GrowthReferenceSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Internal asset adapter for the Growth Measurement implementation.
 *
 * Missing/malformed assets produce no reference facts; callers never open or
 * interpret the curve file.
 */
@Singleton
class GrowthReferenceCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
) : GrowthReferenceSource {
    private val cache = mutableMapOf<Pair<RecordType, Sex?>, List<GrowthReferenceBand>>()

    override fun bands(type: RecordType, sex: Sex?): List<GrowthReferenceBand> =
        cache.getOrPut(type to sex) { load(type, sex) }

    private fun load(type: RecordType, sex: Sex?): List<GrowthReferenceBand> {
        if (type == RecordType.HEAD) return emptyList()
        val metricKey = when (type) {
            RecordType.WEIGHT -> "weight_kg"
            RecordType.HEIGHT -> "length_cm"
            else -> return emptyList()
        }
        val sexKey = growthReferenceSexKey(sex) ?: return emptyList()
        return runCatching {
            val source = context.assets.open(ASSET_PATH)
                .bufferedReader()
                .use { it.readText() }
            parseGrowthReferenceBands(
                source,
                sexKey,
                metricKey,
            )
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val ASSET_PATH = "curves/who_percentiles_0_24.json"
    }
}

internal fun growthReferenceSexKey(sex: Sex?): String? = when (sex) {
    Sex.MALE -> "boys"
    Sex.FEMALE -> "girls"
    Sex.UNKNOWN, null -> null
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class GrowthReferenceModule {
    @Binds
    abstract fun bindGrowthReferenceSource(
        implementation: GrowthReferenceCatalog,
    ): GrowthReferenceSource
}

internal fun parseGrowthReferenceBands(
    source: String,
    sexKey: String,
    metricKey: String,
): List<GrowthReferenceBand> = runCatching {
    val values = Json.parseToJsonElement(source)
        .jsonObject
        .getValue(sexKey)
        .jsonObject
        .getValue(metricKey)
        .jsonArray
    values.map { entry ->
        val value = entry.jsonObject
        GrowthReferenceBand(
            month = value.getValue("m").jsonPrimitive.double.toFloat(),
            p3 = value.getValue("p3").jsonPrimitive.double.toFloat(),
            p50 = value.getValue("p50").jsonPrimitive.double.toFloat(),
            p97 = value.getValue("p97").jsonPrimitive.double.toFloat(),
        )
    }
}.getOrDefault(emptyList())
