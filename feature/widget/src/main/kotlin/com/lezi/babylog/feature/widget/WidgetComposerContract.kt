package com.lezi.babylog.feature.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lezi.babylog.core.model.RecordType

object WidgetComposerContract {
    const val ACTION_OPEN_RECORD_COMPOSER =
        "com.lezi.babylog.action.OPEN_RECORD_COMPOSER"
    const val EXTRA_BABY_ID = "com.lezi.babylog.extra.BABY_ID"
    const val EXTRA_RECORD_TYPE = "com.lezi.babylog.extra.RECORD_TYPE"
    const val MAIN_ACTIVITY_CLASS = "com.lezi.babylog.MainActivity"

    fun createIntent(
        context: Context,
        babyId: Long,
        type: RecordType,
    ): Intent {
        val route = WidgetComposerRoute(babyId, type)
        return Intent(ACTION_OPEN_RECORD_COMPOSER, Uri.parse(route.dataUri))
            .setComponent(ComponentName(context.packageName, MAIN_ACTIVITY_CLASS))
            .putExtra(EXTRA_BABY_ID, babyId)
            .putExtra(EXTRA_RECORD_TYPE, type.key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    fun createOpenAppIntent(context: Context, widgetId: Int): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("lezi://widget/$widgetId"))
            .setComponent(ComponentName(context.packageName, MAIN_ACTIVITY_CLASS))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /**
     * Decodes an external widget launch request without granting it any navigation authority.
     * The canonical route must agree with the duplicated extras so malformed/spoofed requests
     * fail closed before the app asks the user for confirmation.
     */
    fun parseUntrusted(intent: Intent?): WidgetComposerTarget? {
        val data = intent?.data
        return decodeUntrustedTarget(
            WidgetComposerIntentSnapshot(
                action = intent?.action,
                scheme = data?.scheme,
                host = data?.host,
                pathSegments = data?.pathSegments.orEmpty(),
                queryBabyId = data?.getQueryParameters("babyId")?.singleOrNull(),
                queryRecordTypeKey = data?.getQueryParameters("type")?.singleOrNull(),
                extraBabyId = intent
                    ?.takeIf { it.hasExtra(EXTRA_BABY_ID) }
                    ?.getLongExtra(EXTRA_BABY_ID, -1L),
                extraRecordTypeKey = intent?.getStringExtra(EXTRA_RECORD_TYPE),
            ),
        )
    }

    @Deprecated(
        message = "External widget intents are untrusted; use parseUntrusted",
        replaceWith = ReplaceWith("parseUntrusted(intent)"),
    )
    fun parse(intent: Intent?): WidgetComposerTarget? = parseUntrusted(intent)

    internal fun decodeUntrustedTarget(
        snapshot: WidgetComposerIntentSnapshot,
    ): WidgetComposerTarget? {
        if (snapshot.action != ACTION_OPEN_RECORD_COMPOSER) return null
        if (snapshot.scheme != "lezi" || snapshot.host != "composer") return null
        if (snapshot.pathSegments != listOf("new")) return null
        val extraBabyId = snapshot.extraBabyId ?: return null
        val queryBabyId = snapshot.queryBabyId?.toLongOrNull() ?: return null
        if (queryBabyId != extraBabyId) return null
        if (snapshot.queryRecordTypeKey != snapshot.extraRecordTypeKey) return null
        return decodeTarget(
            babyId = extraBabyId,
            recordTypeKey = snapshot.extraRecordTypeKey,
        )
    }

    internal fun decodeTarget(
        babyId: Long,
        recordTypeKey: String?,
    ): WidgetComposerTarget? {
        if (babyId <= 0) return null
        val type = recordTypeKey?.let(RecordType::fromKey) ?: return null
        return WidgetComposerTarget(babyId, type)
    }
}

internal data class WidgetComposerIntentSnapshot(
    val action: String?,
    val scheme: String?,
    val host: String?,
    val pathSegments: List<String>,
    val queryBabyId: String?,
    val queryRecordTypeKey: String?,
    val extraBabyId: Long?,
    val extraRecordTypeKey: String?,
)

data class WidgetComposerTarget(
    val babyId: Long,
    val type: RecordType,
)

internal data class WidgetComposerRoute(
    val babyId: Long,
    val type: RecordType,
) {
    init {
        require(babyId > 0) { "babyId must be positive" }
    }

    val dataUri: String
        get() = "lezi://composer/new?babyId=$babyId&type=${type.key}"
}
