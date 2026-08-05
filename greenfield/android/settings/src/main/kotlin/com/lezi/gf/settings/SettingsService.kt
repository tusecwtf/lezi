package com.lezi.gf.settings

import kotlinx.serialization.Serializable

@Serializable
enum class UiTemplate { WARM, JOURNAL }

@Serializable
enum class Handedness { LEFT, RIGHT }

@Serializable
enum class TimeFormat { H12, H24 }

@Serializable
data class LocalSettings(
    val template: UiTemplate = UiTemplate.WARM,
    val darkTheme: Boolean = false,
    val handedness: Handedness = Handedness.RIGHT,
    val timeFormat: TimeFormat = TimeFormat.H24,
    val weekStartsOnMonday: Boolean = true,
    val useDayAgeMode: Boolean = true,
    val reduceMotion: Boolean = false,
    val showFeverHint: Boolean = false,
    val systemCalendarProjection: Boolean = true,
    val localReminders: Boolean = true,
    val amountStepMl: Int = 5,
    val timelineNewestFirst: Boolean = true,
    val localDataContractVersion: Int = com.lezi.gf.kernel.LocalDataContract.CURRENT,
)

/**
 * Local-only settings — never family-synced (theme/dark/layout stay on device).
 */
class SettingsService(initial: LocalSettings = LocalSettings()) {
    @Volatile
    private var settings: LocalSettings = initial

    fun get(): LocalSettings = settings

    fun setTemplate(t: UiTemplate) {
        settings = settings.copy(template = t)
    }

    fun setDark(dark: Boolean) {
        settings = settings.copy(darkTheme = dark)
    }

    fun update(transform: (LocalSettings) -> LocalSettings) {
        settings = transform(settings)
    }

    /** Unjoined devices must not use unauthenticated update channel. */
    fun canCheckAppUpdate(joined: Boolean): Boolean = joined

    fun unjoinedUpdateHonestyMessage(): String =
        "未加入家庭时无法检查更新。请先连接家庭服务器后再试。"
}
