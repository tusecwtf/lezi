package com.lezi.babylog.feature.growth

import android.content.Context
import com.lezi.babylog.core.model.GrowthReferenceBand
import com.lezi.babylog.core.model.GrowthReferenceSeries
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.Sex
import com.lezi.babylog.domain.growth.GrowthReferenceSource
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
    private val cache = GrowthReferenceCache(::load)

    override fun reference(type: RecordType, sex: Sex?): GrowthReferenceSeries? =
        cache.get(type, sex)

    private fun load(type: RecordType, sex: Sex?): GrowthReferenceSeries? {
        val metricKey = when (type) {
            RecordType.WEIGHT -> "weight_kg"
            RecordType.HEIGHT -> "length_height_cm"
            else -> return null
        }
        val sexKey = growthReferenceSexKey(sex) ?: return null
        return runCatching {
            val source = context.assets.open(ASSET_PATH)
                .bufferedReader()
                .use { it.readText() }
            buildUnderSevenReferenceSeries(
                parseGrowthReferenceBands(
                    source,
                    sexKey,
                    metricKey,
                ),
            )
        }.getOrNull()
    }

    private companion object {
        const val ASSET_PATH = "curves/wst_423_2022_percentiles_under7.json"
    }
}

/**
 * One parsed series per type and sex, including a cached absence.
 * Asset reads stay off the map's publication so two callers cannot observe a half-written entry.
 */
internal class GrowthReferenceCache(
    private val load: (RecordType, Sex?) -> GrowthReferenceSeries?,
) {
    private val values = HashMap<Pair<RecordType, Sex?>, GrowthReferenceSeries?>()

    fun get(type: RecordType, sex: Sex?): GrowthReferenceSeries? {
        val key = type to sex
        return synchronized(values) {
            if (values.containsKey(key)) {
                values[key]
            } else {
                val loaded = load(type, sex)
                values[key] = loaded
                loaded
            }
        }
    }
}

internal fun buildUnderSevenReferenceSeries(
    publishedBands: List<GrowthReferenceBand>,
): GrowthReferenceSeries? {
    if (publishedBands.isEmpty() || publishedBands.last().month != LAST_PUBLISHED_MONTH) {
        return null
    }
    return GrowthReferenceSeries(
        bands = publishedBands + publishedBands.last().copy(month = UNDER_SEVEN_MONTH_EXCLUSIVE),
        validUntilMonthExclusive = UNDER_SEVEN_MONTH_EXCLUSIVE,
    )
}

private const val LAST_PUBLISHED_MONTH = 81f
internal const val UNDER_SEVEN_MONTH_EXCLUSIVE = 84f

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
