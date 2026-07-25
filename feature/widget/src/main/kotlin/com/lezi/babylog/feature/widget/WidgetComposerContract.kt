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

    fun parse(intent: Intent?): WidgetComposerTarget? {
        if (intent?.action != ACTION_OPEN_RECORD_COMPOSER) return null
        return decodeTarget(
            babyId = intent.getLongExtra(EXTRA_BABY_ID, -1L),
            recordTypeKey = intent.getStringExtra(EXTRA_RECORD_TYPE),
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
