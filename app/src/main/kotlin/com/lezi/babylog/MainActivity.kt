package com.lezi.babylog

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Person
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.common.DefaultLocalDataGate
import com.lezi.babylog.core.common.LocalDataUpgradeState
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.core.model.elderModeEnabled

import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.designsystem.AppBrandBar
import com.lezi.babylog.designsystem.LeziEasing
import com.lezi.babylog.designsystem.LeziMotion
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.LeziTextButtonTone
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.leziMotionMillis
import com.lezi.babylog.designsystem.rememberLeziHaptics
import com.lezi.babylog.designsystem.readableContentColor
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.calendar.SystemCalendarConfigurationCoordinator
import com.lezi.babylog.domain.carelog.babyAgeShortLabel
import com.lezi.babylog.feature.export.ExportRoute
import com.lezi.babylog.domain.carelog.UnresolvedInboxIds
import com.lezi.babylog.feature.family.conflict.ConflictEditTarget
import com.lezi.babylog.feature.family.conflict.ConflictInboxRoute
import com.lezi.babylog.feature.family.conflict.ConflictResolverRoute
import com.lezi.babylog.feature.family.conflict.LocalUnresolvedResolverRoute
import com.lezi.babylog.feature.family.FamilyRoute
import com.lezi.babylog.feature.growth.GrowthRoute
import com.lezi.babylog.feature.log.composer.ComposerCreateIntent
import com.lezi.babylog.feature.log.LogRoute
import com.lezi.babylog.feature.log.composer.RecordComposerHost
import com.lezi.babylog.feature.log.composer.RecordComposerRequest
import com.lezi.babylog.feature.log.composer.recordComposerOpenRequest
import com.lezi.babylog.feature.log.composer.restorableComposerRequest
import com.lezi.babylog.feature.log.dock.quickDockSnackbarBottomInset
import com.lezi.babylog.feature.onboarding.OnboardingBabyStepHold
import com.lezi.babylog.feature.onboarding.OnboardingRoute
import com.lezi.babylog.feature.search.SearchRoute
import com.lezi.babylog.feature.settings.SettingsRoute
import com.lezi.babylog.feature.settings.calendar.CalendarRoute
import com.lezi.babylog.feature.settings.calendar.SystemCalendarSetupDialog
import com.lezi.babylog.feature.summary.SummaryRoute
import com.lezi.babylog.core.model.TimerHandoffSeed
import com.lezi.babylog.feature.timer.NursingTimerService
import com.lezi.babylog.feature.timer.TimerRoute
import com.lezi.babylog.feature.widget.CareWidgetRefreshController
import com.lezi.babylog.sync.AppUpdateCheckResult
import com.lezi.babylog.sync.AppUpdateInstallResult
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.backend.ClientUpdateRequiredException
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.ForcedAppUpdateState
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.appupdate.forceShellNeedsSessionRecovery
import com.lezi.babylog.sync.appupdate.forcedUpdateDialogBody
import com.lezi.babylog.sync.appupdate.forcedUpdateLanInviteGuidance
import com.lezi.babylog.sync.appupdate.forcedUpdateLanInviteOpenLabel
import com.lezi.babylog.sync.appupdate.forcedUpdatePackageUnknownBody
import com.lezi.babylog.sync.appupdate.forcedUpdateRetryCheckLabel
import com.lezi.babylog.sync.appupdate.forcedUpdateSessionRecoveryLabel
import com.lezi.babylog.sync.appupdate.forcedUpdateTitle
import com.lezi.babylog.sync.appupdate.lanInviteApkDownloadUrl
import com.lezi.babylog.sync.backend.ReauthRequiredException
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var localDataGate: DefaultLocalDataGate
    @Inject lateinit var settingsStore: SettingsStore
    private val pendingExternalNavigation =
        mutableStateOf<UntrustedExternalNavigation?>(null)

    /**
     * Notification 「结束」 request (timer ticket 12). The completion form is the
     * confirmation, so unlike external deep links this routes without a dialog —
     * nothing is saved or dropped until the user confirms in the form.
     */
    private val pendingTimerFinishRequest = mutableStateOf(false)

    override fun attachBaseContext(newBase: Context) {
        val chineseLocale = Locale.forLanguageTag("zh-CN")
        Locale.setDefault(chineseLocale)
        val localizedConfiguration = Configuration(newBase.resources.configuration).apply {
            setLocale(chineseLocale)
        }
        super.attachBaseContext(newBase.createConfigurationContext(localizedConfiguration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        // Bridge the system launch window to the local-data gate (motion-polish
        // ticket 01): hold it only while the gate is Checking, so cold start goes
        // launch window → first real frame with no spinner flash. No branded
        // splash page, no artificial delay (CONTEXT.md 本地数据升级门禁);
        // Snapshotting/Migrating/Blocked release immediately to the in-app UI.
        splashScreen.setKeepOnScreenCondition {
            localDataGate.state.value is LocalDataUpgradeState.Checking
        }
        super.onCreate(savedInstanceState)
        pendingExternalNavigation.value = parseUntrustedExternalNavigation(intent)
        pendingTimerFinishRequest.value = isNursingTimerFinishIntent(intent)
        enableEdgeToEdge()
        setContent {
            val localDataState by localDataGate.state.collectAsStateWithLifecycle()
            val gateScope = rememberCoroutineScope()
            LaunchedEffect(Unit) { localDataGate.ensureReady() }
            if (localDataState is LocalDataUpgradeState.Ready) {
                val vm: RootViewModel = hiltViewModel()
                val ui by vm.ui.collectAsStateWithLifecycle()
                // Before the first ui emission, seed the theme from DataStore so the
                // first frame already uses the user's dark/visual/elder palette
                // instead of RootUi() defaults (motion-polish ticket 01).
                val pendingSettings by settingsStore.settings.collectAsStateWithLifecycle(
                    SettingsLocal(),
                )
                val themeUi = ui ?: RootUi(
                    darkMode = pendingSettings.darkMode,
                    visualStyle = pendingSettings.visualStyle,
                    elderMode = pendingSettings.elderMode,
                )
                val systemDark = isSystemInDarkTheme()
                val dark = leziDarkTheme(themeUi.darkMode, systemDark)
                LeziTheme(
                    darkTheme = dark,
                    babyThemeArgb = themeUi.baby?.themeColorArgb,
                    visualStyle = themeUi.visualStyle,
                    elderMode = themeUi.elderMode,
                ) {
                    val transparent = Color.Transparent.toArgb()
                    val navigationScrim = MaterialTheme.colorScheme.surface.toArgb()
                    // Top bar background comes from the shared top-bar chrome (see
                    // leziTopBarBackground in AppHeader.kt): dark → surface,
                    // light → babyAccent. Pick status bar icon color from its luminance
                    // so dark icons never sit on the dark accent fill. The main
                    // scaffold's route-aware SideEffect refines this once it composes;
                    // this one keeps the onboarding shell correct.
                    val topBarBackground = leziTopBarBackground(dark)
                    val topBarNeedsLightIcons =
                        com.lezi.babylog.designsystem.readableContentColor(topBarBackground) ==
                            Color.White
                    SideEffect {
                        this@MainActivity.enableEdgeToEdge(
                            statusBarStyle = if (topBarNeedsLightIcons) {
                                SystemBarStyle.dark(transparent)
                            } else {
                                SystemBarStyle.light(transparent, transparent)
                            },
                            navigationBarStyle = if (dark) {
                                SystemBarStyle.dark(navigationScrim)
                            } else {
                                SystemBarStyle.light(navigationScrim, navigationScrim)
                            },
                        )
                    }
                    LeziRoot(
                        vm = vm,
                        dark = dark,
                        externalNavigationRequest = pendingExternalNavigation.value,
                        onExternalNavigationConsumed = { consumed ->
                            if (pendingExternalNavigation.value == consumed) {
                                pendingExternalNavigation.value = null
                            }
                        },
                        pendingTimerFinishRequest = pendingTimerFinishRequest.value,
                        onTimerFinishRequestConsumed = {
                            pendingTimerFinishRequest.value = false
                        },
                    )
                }
            } else {
                val settings by settingsStore.settings.collectAsStateWithLifecycle(
                    SettingsLocal(),
                )
                // Same dark-mode source as the Ready branch (DataStore setting, not
                // system), so a dark-mode user never sees the gate screen flip light.
                val gateDark = leziDarkTheme(settings.darkMode, isSystemInDarkTheme())
                LeziTheme(
                    darkTheme = gateDark,
                    elderMode = settings.elderMode,
                ) {
                    val transparent = Color.Transparent.toArgb()
                    val navigationScrim = MaterialTheme.colorScheme.surface.toArgb()
                    val gateNeedsLightIcons =
                        com.lezi.babylog.designsystem.readableContentColor(
                            MaterialTheme.colorScheme.surface,
                        ) == Color.White
                    SideEffect {
                        this@MainActivity.enableEdgeToEdge(
                            statusBarStyle = if (gateNeedsLightIcons) {
                                SystemBarStyle.dark(transparent)
                            } else {
                                SystemBarStyle.light(transparent, transparent)
                            },
                            navigationBarStyle = if (gateDark) {
                                SystemBarStyle.dark(navigationScrim)
                            } else {
                                SystemBarStyle.light(navigationScrim, navigationScrim)
                            },
                        )
                    }
                    LocalDataUpgradeScreen(
                        state = localDataState,
                        onRetry = { gateScope.launch { localDataGate.retry() } },
                        onShareDiagnostics = {
                            shareLocalDataDiagnostics(
                                this@MainActivity,
                                localDataGate.diagnosticReport(),
                            )
                        },
                        onClearApplicationData = {
                            clearLeziApplicationData(this@MainActivity)
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingExternalNavigation.value = parseUntrustedExternalNavigation(intent)
        if (isNursingTimerFinishIntent(intent)) {
            pendingTimerFinishRequest.value = true
        }
    }
}

/**
 * Own-notification 「结束」 tap: the launcher intent carries the timer finish extra.
 * Trusted in-app navigation — the worst a spoofed intent can do is open the timer
 * page with the current session's completion form, which still requires an explicit
 * save (and shows nothing without a live timer session).
 */
private fun isNursingTimerFinishIntent(intent: Intent?): Boolean =
    intent?.hasExtra(NursingTimerService.EXTRA_NOTIFICATION_FINISH) == true

data class PendingFulfillPlan(
    val planId: Long?,
    val clientUuid: String,
)

data class RootUi(
    val hasBaby: Boolean = false,
    val familyRole: FamilyRole = FamilyRole.None,
    val baby: Baby? = null,
    val babies: List<Baby> = emptyList(),
    val sleeping: Boolean = false,
    val darkMode: String = "system",
    val visualStyle: String = "warm",
    val elderMode: String = "off",
    val selectedDate: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val composerRequest: RecordComposerRequest? = null,
)

internal fun rootUiFromLocalSettings(
    hasBaby: Boolean,
    current: Baby?,
    babies: List<Baby>,
    settings: SettingsLocal,
    day: LocalDate,
): RootUi = RootUi(
    hasBaby = hasBaby,
    baby = current,
    babies = babies,
    darkMode = settings.darkMode,
    visualStyle = settings.visualStyle,
    elderMode = settings.elderMode,
    selectedDate = day,
)

/** Root-owned selected-day state shared by timeline effects and external date controls. */
internal class RootSelectedDateOwner(
    initialSelectedDate: LocalDate,
    initialToday: LocalDate,
    private val persist: (LocalDate) -> Unit = {},
) {
    private val mutableSelectedDate = MutableStateFlow(
        clampSelectedDate(initialSelectedDate, initialToday),
    )
    val selectedDate: StateFlow<LocalDate> = mutableSelectedDate.asStateFlow()

    init {
        persist(mutableSelectedDate.value)
    }

    /** Returns false for a repeated value, preventing the Root → Log feedback path from looping. */
    fun select(requested: LocalDate, today: LocalDate): Boolean {
        val selected = clampSelectedDate(requested, today)
        if (selected == mutableSelectedDate.value) return false
        mutableSelectedDate.value = selected
        persist(selected)
        return true
    }

    fun shift(deltaDays: Long, today: LocalDate): Boolean =
        select(mutableSelectedDate.value.plusDays(deltaDays), today)
}

@HiltViewModel
class RootViewModel @Inject constructor(
    private val careLog: CareLog,
    private val syncPort: SyncPort,
    private val settings: SettingsStore,
    private val systemCalendarConfiguration: SystemCalendarConfigurationCoordinator,
    private val savedStateHandle: SavedStateHandle,
    // Lazy: the controller's constructor reads the widget SharedPreferences
    // XML from disk; building it eagerly puts that read on the cold-start
    // main thread. Only refreshWidgets() ever needs it.
    private val widgetRefreshController: dagger.Lazy<CareWidgetRefreshController>,
    private val onboardingBabyStepHold: OnboardingBabyStepHold,
) : ViewModel() {
    val pendingCreateBaby: StateFlow<Boolean> = onboardingBabyStepHold.pendingCreateBaby

    init {
        viewModelScope.launch {
            careLog.observeHasBaby().collect { hasBaby ->
                if (hasBaby) onboardingBabyStepHold.disarm()
            }
        }
    }
    private val zone = ZoneId.systemDefault()
    private val todayFlow = MutableStateFlow(liveCalendarToday())
    private val selectedDateOwner = RootSelectedDateOwner(
        initialSelectedDate = restoreSelectedDate(
            restoredSelected = savedStateHandle.get<Long>(SELECTED_DATE_KEY)
                ?.let(LocalDate::ofEpochDay),
            restoredToday = savedStateHandle.get<Long>(TODAY_KEY)
                ?.let(LocalDate::ofEpochDay),
            liveToday = todayFlow.value,
        ),
        initialToday = todayFlow.value,
        persist = { selected -> persistDatePair(selected, todayFlow.value) },
    )
    private val dayFlow = selectedDateOwner.selectedDate
    private val calendarMonthFlow = MutableStateFlow(YearMonth.from(dayFlow.value))
    private val composerRequestFlow = savedStateHandle.getStateFlow<RecordComposerRequest?>(
        COMPOSER_REQUEST_KEY,
        null,
    )

    private val localBaseUi = combine(
        careLog.observeHasBaby(),
        careLog.observeCurrentBaby(),
        careLog.observeBabies(),
        settings.settings,
        dayFlow,
    ) { has, current, babies, s, day ->
        rootUiFromLocalSettings(has, current, babies, s, day)
    }

    private val baseUi = combine(localBaseUi, syncPort.sessionPresentation()) { base, session ->
        base.copy(familyRole = session.role)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val sleepingBaby = careLog.observeCurrentBaby().flatMapLatest { baby ->
        if (baby == null) {
            flowOf(null to false)
        } else {
            careLog.observeOpenSleep(baby.id).map { openSleep ->
                baby.id to (openSleep != null)
            }
        }
    }

    /** Month dots for the header calendar dialog; collected only while that dialog is open. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val calendarRecordDays: StateFlow<Set<LocalDate>> = combine(
        careLog.observeCurrentBaby(),
        calendarMonthFlow,
    ) { baby, month ->
        baby?.id to month
    }.flatMapLatest { (babyId, month) ->
        if (babyId == null) {
            flowOf(emptySet<LocalDate>())
        } else {
            careLog.observeRecords(
                babyId = babyId,
                startDayInclusive = month.atDay(1),
                endDayExclusive = month.plusMonths(1).atDay(1),
                zone = zone,
            ).map { records ->
                records
                    .asSequence()
                    .map { record ->
                        Instant.ofEpochMilli(record.timestamp).atZone(zone).toLocalDate()
                    }
                    .toSet()
            }.distinctUntilChanged()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val shallowSyncLine = syncPort.shallowStatus()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            com.lezi.babylog.sync.session.ShallowSyncLine(
                state = com.lezi.babylog.sync.session.ShallowSyncState.Unjoined,
                text = "",
            ),
        )

    /** Root shell state; null until the first Room/DataStore/session emission resolves. */
    val ui: StateFlow<RootUi?> = combine(
        baseUi,
        sleepingBaby,
        todayFlow,
        composerRequestFlow,
    ) { base, (sleepingBabyId, sleeping), today, composerRequest ->
        base.copy(
            sleeping = sleepingBabyId == base.baby?.id && sleeping,
            today = today,
            composerRequest = restorableComposerRequest(composerRequest),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Device-local system calendar target id (ticket 21); not family-synced. */
    val systemCalendarId = settings.settings
        .map { it.systemCalendarId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val systemCalendarDisclosureLevel = settings.settings
        .map { it.systemCalendarDisclosureLevel }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 2)

    /** Root-level force-update surface; null when not forced. */
    val forcedAppUpdate: StateFlow<ForcedAppUpdateState?> =
        syncPort.availableForcedAppUpdate()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Session snapshot for force-shell recovery / LAN invite guidance.
     * Force shell is retained across reauth; UI must still allow re-login.
     * Prefer [SyncPort.forcedUpdateLanInviteHost] (restore-candidate origin after CUR)
     * over the retained session host so empty-server restore can still offer 8767.
     */
    val forcedUpdateSessionRecovery: StateFlow<ForcedUpdateSessionRecovery> =
        combine(
            syncPort.sessionPresentation(),
            syncPort.forcedUpdateLanInviteHost(),
        ) { session, inviteHostOverride ->
            val inviteHost = inviteHostOverride?.trim()?.takeIf { it.isNotEmpty() }
                ?: session.serverHost
            val inviteUrl = lanInviteApkDownloadUrl(inviteHost)
            ForcedUpdateSessionRecovery(
                needsSessionRecovery = forceShellNeedsSessionRecovery(
                    isJoined = session.isJoined,
                    reauthRequired = session.reauthRequired,
                    retainsFamilyIdentity = session.reauthRequired &&
                        session.familyId.isNotBlank() &&
                        session.membershipId.isNotBlank() &&
                        session.baseUrl.isNotBlank(),
                ),
                lanInviteApkUrl = inviteUrl,
            )
        }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ForcedUpdateSessionRecovery(),
            )

    private val _forcedUpdateBusy = MutableStateFlow(false)
    val forcedUpdateBusy: StateFlow<Boolean> = _forcedUpdateBusy.asStateFlow()
    private val _forcedUpdateMessage = MutableStateFlow<String?>(null)
    val forcedUpdateMessage: StateFlow<String?> = _forcedUpdateMessage.asStateFlow()
    private val _forcedUpdateNeedsInstallPermission = MutableStateFlow(false)
    val forcedUpdateNeedsInstallPermission: StateFlow<Boolean> =
        _forcedUpdateNeedsInstallPermission.asStateFlow()

    /**
     * When true, full-screen force sink is lowered so account/onboarding reauth UI
     * under the shell remains usable. Force state itself is never cleared here.
     */
    private val _forceShellSessionRecoveryExpanded = MutableStateFlow(false)
    val forceShellSessionRecoveryExpanded: StateFlow<Boolean> =
        _forceShellSessionRecoveryExpanded.asStateFlow()

    fun expandForceShellSessionRecovery() {
        _forceShellSessionRecoveryExpanded.value = true
        _forcedUpdateMessage.value =
            "请在下方完成重新登录或信任校验；完成后可继续安装更新。强制更新不会取消。"
    }

    fun collapseForceShellSessionRecovery() {
        _forceShellSessionRecoveryExpanded.value = false
    }

    fun installForcedAppUpdate(metadata: AppUpdateMetadata) {
        if (_forcedUpdateBusy.value) return
        viewModelScope.launch {
            _forcedUpdateBusy.value = true
            _forcedUpdateNeedsInstallPermission.value = false
            _forcedUpdateMessage.value = "正在从家庭服务器下载更新包…"
            try {
                val session = syncPort.sessionPresentation().first()
                if (!session.isJoined) {
                    _forcedUpdateNeedsInstallPermission.value = false
                    _forcedUpdateMessage.value =
                        "登录已失效，请先重新登录家庭后再安装更新。"
                    _forceShellSessionRecoveryExpanded.value = true
                    return@launch
                }
                val result = syncPort.installAvailableAppUpdate(metadata)
                result.fold(
                    onSuccess = { install ->
                        when (install) {
                            AppUpdateInstallResult.SessionStarted -> {
                                _forcedUpdateNeedsInstallPermission.value = false
                                _forcedUpdateMessage.value =
                                    "请在系统界面确认安装。安装结束后可删除通知；乐记不会在本机留下更新包。"
                            }
                            AppUpdateInstallResult.RequiresInstallPermission -> {
                                _forcedUpdateNeedsInstallPermission.value = true
                                _forcedUpdateMessage.value =
                                    "请允许乐记安装应用，然后再试一次立即更新。"
                            }
                        }
                    },
                    onFailure = { error ->
                        _forcedUpdateNeedsInstallPermission.value = false
                        if (error is ReauthRequiredException) {
                            _forceShellSessionRecoveryExpanded.value = true
                            _forcedUpdateMessage.value =
                                "登录已失效，请先重新登录家庭后再安装更新。"
                        } else {
                            val inviteHost = syncPort.forcedUpdateLanInviteHost().first()
                                ?.trim()?.takeIf { it.isNotEmpty() }
                                ?: session.serverHost
                            val invite = lanInviteApkDownloadUrl(inviteHost)
                            _forcedUpdateMessage.value = buildString {
                                append(productUiError(error, "下载或安装失败，请稍后重试"))
                                if (invite != null) {
                                    append('\n')
                                    append(forcedUpdateLanInviteGuidance(invite))
                                }
                            }
                        }
                    },
                )
            } finally {
                _forcedUpdateBusy.value = false
            }
        }
    }

    /**
     * Re-check update metadata while under a force shell (especially [ForcedAppUpdateState.PackageUnknown]).
     * Does not dismiss the force surface; successful Forced metadata upgrades it via [forcedAppUpdate].
     * Never surfaces "当前已是最新版本" while a force shell remains (Result and shell stay force-honest).
     */
    fun retryForcedAppUpdateCheck() {
        if (_forcedUpdateBusy.value) return
        viewModelScope.launch {
            _forcedUpdateBusy.value = true
            _forcedUpdateNeedsInstallPermission.value = false
            _forcedUpdateMessage.value = "正在检查更新…"
            try {
                val result = syncPort.checkAppUpdate()
                // Prefer live port state after classify (StateFlow may lag one frame).
                val shellAfter = syncPort.availableForcedAppUpdate().first()
                result.fold(
                    onSuccess = { check ->
                        _forcedUpdateMessage.value = when {
                            // Installable forced package upgraded or retained — clear busy copy.
                            check is AppUpdateCheckResult.ForcedUpdate -> null
                            shellAfter is ForcedAppUpdateState.WithPackage -> null
                            // PackageUnknown retained / force-honest Result — never "already latest".
                            check is AppUpdateCheckResult.ForcedPackageUnknown ||
                                shellAfter is ForcedAppUpdateState.PackageUnknown ->
                                "仍须更新乐记，但暂时无法从家庭服务器获取更新包，请再试「重试检查更新」。"
                            check is AppUpdateCheckResult.NotJoined ->
                                "请先连接家庭服务器后再检查更新"
                            // Shell cleared (should be rare on retry path).
                            check is AppUpdateCheckResult.UpToDate -> "当前已是最新版本"
                            check is AppUpdateCheckResult.OptionalUpdate -> null
                            else -> null
                        }
                    },
                    onFailure = { error ->
                        _forcedUpdateMessage.value = when {
                            error is ClientUpdateRequiredException ||
                                shellAfter != null ->
                                "仍须更新乐记，但暂时无法从家庭服务器获取更新包，请再试「重试检查更新」。"
                            else -> productUiError(error, "检查更新失败，请稍后重试")
                        }
                    },
                )
            } finally {
                _forcedUpdateBusy.value = false
            }
        }
    }

    private val calendarSetupCommand = com.lezi.babylog.feature.settings.calendar.SystemCalendarSetupCommand(systemCalendarConfiguration)
    val calendarSetupState = calendarSetupCommand.state

    fun consumeCalendarSetupResult() = calendarSetupCommand.consume()

    fun confirmSystemCalendar(calendarId: String, disclosureLevel: Int) = viewModelScope.launch {
        calendarSetupCommand.confirm(com.lezi.babylog.feature.settings.calendar.SystemCalendarSetupSelection(calendarId, disclosureLevel))
    }

    fun disableSystemCalendar() = viewModelScope.launch { calendarSetupCommand.disable() }

    fun cycleBaby() {
        viewModelScope.launch {
            val state = ui.value ?: return@launch
            val nextId = nextSiblingId(state.babies.map(Baby::id), state.baby?.id)
                ?: return@launch
            // A clear racing the switch rethrows the epoch exceptions; a
            // member switching without permission throws — either way the
            // header simply keeps the current baby instead of crashing.
            runCatching { careLog.setCurrentBaby(nextId) }
                .onFailure { error -> if (error is CancellationException) throw error }
        }
    }

    fun jumpSiblingSameDayAge() {
        viewModelScope.launch {
            val state = ui.value ?: return@launch
            val current = state.baby ?: return@launch
            val nextId = nextSiblingId(state.babies.map(Baby::id), current.id)
                ?: return@launch
            val next = state.babies.first { it.id == nextId }
            refreshToday()
            val targetDate = siblingSameDayAgeDate(
                currentBirthdayEpochDay = current.birthdayEpochDay,
                siblingBirthdayEpochDay = next.birthdayEpochDay,
                selectedDate = dayFlow.value,
                today = todayFlow.value,
            )
            runCatching { careLog.setCurrentBaby(next.id) }
                .onFailure { error -> if (error is CancellationException) throw error }
                .onSuccess { updateSelectedDate(targetDate) }
        }
    }

    fun toggleDark() {
        viewModelScope.launch {
            val next = when (ui.value?.darkMode) {
                "dark" -> "light"
                else -> "dark"
            }
            settings.setDarkMode(next)
        }
    }

    fun shiftDay(delta: Long) {
        refreshToday()
        selectedDateOwner.shift(delta, todayFlow.value)
    }

    fun setDay(day: LocalDate) {
        refreshToday()
        updateSelectedDate(day)
    }

    fun goToday() {
        refreshToday()
        updateSelectedDate(todayFlow.value)
    }

    fun setCalendarMonth(month: YearMonth) {
        refreshToday()
        val currentMonth = YearMonth.from(todayFlow.value)
        calendarMonthFlow.value = if (month > currentMonth) currentMonth else month
    }

    fun refreshToday() {
        val live = liveCalendarToday()
        val previous = todayFlow.value
        val selected = dayFlow.value
        val snapshot = applyCalendarToday(
            liveToday = live,
            previousToday = previous,
            selectedDate = selected,
        )
        val todayChanged = snapshot.today != previous
        val selectedChanged = snapshot.selectedDate != selected
        if (!todayChanged && !selectedChanged) return
        todayFlow.value = snapshot.today
        if (selectedChanged) {
            selectedDateOwner.select(snapshot.selectedDate, snapshot.today)
        } else {
            persistDatePair(selected, snapshot.today)
        }
    }

    fun openWidgetBaby(babyId: Long) {
        viewModelScope.launch {
            runCatching { careLog.setCurrentBaby(babyId) }
                .onFailure { error -> if (error is CancellationException) throw error }
        }
    }

    private var composerNavigationGeneration = 0L

    fun openComposer(request: RecordComposerRequest) {
        if (forcedAppUpdate.value != null) return
        composerNavigationGeneration++
        val restorable = restorableComposerRequest(request) ?: return
        savedStateHandle[COMPOSER_REQUEST_KEY] = restorable
    }

    fun closeComposer() {
        composerNavigationGeneration++
        savedStateHandle[COMPOSER_REQUEST_KEY] = null
    }

    /**
     * Consume the restorable root request after a durable Composer fact/plan write.
     * Idempotent: widgets refresh only when a root request was actually open, so Host
     * re-subscribe / rotation while a post-save stage remains cannot spam refresh.
     */
    fun closeComposerAfterPersist() {
        composerNavigationGeneration++
        val hadRequest = savedStateHandle.get<RecordComposerRequest>(COMPOSER_REQUEST_KEY) != null
        savedStateHandle[COMPOSER_REQUEST_KEY] = null
        if (hadRequest) {
            refreshWidgets()
        }
    }

    /** The retained root owns async navigation; a newer draft/close invalidates delivery. */
    fun openExternalCarePlan(planId: Long?, clientUuid: String) {
        if (forcedAppUpdate.value != null || composerRequestFlow.value != null) return
        val generation = ++composerNavigationGeneration
        viewModelScope.launch {
            val resolved = try {
                planId ?: clientUuid.takeIf { it.isNotBlank() }?.let { resolveCarePlanId(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (generation == composerNavigationGeneration &&
                composerRequestFlow.value == null && resolved != null && resolved > 0L
            ) {
                openComposer(RecordComposerRequest.Fulfill(resolved))
            }
        }
    }

    /** Resolve a stable plan UUID from a notification deep link; null if missing/terminal. */
    suspend fun resolveCarePlanId(clientUuid: String): Long? {
        val plan = careLog.getCarePlanByClientUuid(clientUuid) ?: return null
        if (plan.deletedAt != null) return null
        return plan.id
    }

    fun refreshWidgets() {
        viewModelScope.launch {
            // A destructive local clear racing this refresh rethrows epoch
            // exceptions from the widget engine; swallow them (the
            // coordinator's next event redraws) instead of crashing after a
            // save. Coroutine cancellation keeps its meaning.
            try {
                widgetRefreshController.get().refreshAll()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
            }
        }
    }

    private fun updateSelectedDate(day: LocalDate) {
        selectedDateOwner.select(day, todayFlow.value)
    }

    private fun persistDatePair(selected: LocalDate, today: LocalDate) {
        savedStateHandle[SELECTED_DATE_KEY] = selected.toEpochDay()
        savedStateHandle[TODAY_KEY] = today.toEpochDay()
    }

    private companion object {
        const val SELECTED_DATE_KEY = "root_selected_date_epoch_day"
        const val TODAY_KEY = "root_today_epoch_day"
        const val COMPOSER_REQUEST_KEY = "root_record_composer_request"
    }
}

internal fun clampSelectedDate(
    requested: LocalDate,
    today: LocalDate = LocalDate.now(),
): LocalDate = if (requested.isAfter(today)) today else requested

internal data class CalendarTodaySnapshot(
    val today: LocalDate,
    val selectedDate: LocalDate,
)

internal fun liveCalendarToday(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    LocalDate.now(zone)

internal fun applyCalendarToday(
    liveToday: LocalDate,
    previousToday: LocalDate,
    selectedDate: LocalDate,
): CalendarTodaySnapshot {
    val selected = if (selectedDate == previousToday) {
        liveToday
    } else {
        clampSelectedDate(selectedDate, liveToday)
    }
    return CalendarTodaySnapshot(today = liveToday, selectedDate = selected)
}

internal fun restoreSelectedDate(
    restoredSelected: LocalDate?,
    restoredToday: LocalDate?,
    liveToday: LocalDate,
): LocalDate {
    val selected = restoredSelected ?: liveToday
    if (restoredToday != null && selected == restoredToday) {
        return liveToday
    }
    return clampSelectedDate(selected, liveToday)
}

internal fun millisUntilNextLocalMidnight(now: ZonedDateTime): Long {
    val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
    return Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1_000L)
}

internal fun siblingSameDayAgeDate(
    currentBirthdayEpochDay: Long,
    siblingBirthdayEpochDay: Long,
    selectedDate: LocalDate,
    today: LocalDate = LocalDate.now(),
): LocalDate {
    val currentBirth = LocalDate.ofEpochDay(currentBirthdayEpochDay)
    val siblingBirth = LocalDate.ofEpochDay(siblingBirthdayEpochDay)
    val ageInWholeDays = ChronoUnit.DAYS.between(currentBirth, selectedDate)
    return clampSelectedDate(siblingBirth.plusDays(ageInWholeDays), today)
}

internal fun nextSiblingId(
    babyIds: List<Long>,
    currentId: Long?,
): Long? {
    if (babyIds.size < 2) return null
    val currentIndex = babyIds.indexOf(currentId)
    val next = if (currentIndex >= 0) {
        babyIds[(currentIndex + 1) % babyIds.size]
    } else {
        babyIds.first()
    }
    return next.takeIf { it != currentId }
}

private enum class TopDest(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Log("log", "记录", Icons.Filled.GridView, Icons.Outlined.GridView),
    Summary("summary", "汇总", Icons.Filled.BarChart, Icons.Outlined.BarChart),
    Growth(
        "growth",
        "成长",
        Icons.AutoMirrored.Filled.ShowChart,
        Icons.AutoMirrored.Outlined.ShowChart,
    ),
    Family("family", "账户", Icons.Filled.Person, Icons.Outlined.Person),
    Settings("settings", "菜单", Icons.Filled.MoreHoriz, Icons.Outlined.MoreHoriz),
}

internal data class ConflictOverlayState(
    val inboxVisible: Boolean = false,
    val resolverConflictId: String? = null,
) {
    fun openInbox() = ConflictOverlayState(inboxVisible = true)

    fun openResolver(conflictId: String): ConflictOverlayState {
        require(conflictId.isNotBlank())
        return ConflictOverlayState(resolverConflictId = conflictId)
    }

    fun dismissInbox() = copy(inboxVisible = false)

    fun dismissResolver() = copy(resolverConflictId = null)
}

private val ConflictOverlayStateSaver = listSaver<ConflictOverlayState, String>(
    save = { listOf(if (it.inboxVisible) "1" else "0", it.resolverConflictId.orEmpty()) },
    restore = { values ->
        ConflictOverlayState(
            inboxVisible = values.firstOrNull() == "1",
            resolverConflictId = values.getOrNull(1)?.ifEmpty { null },
        )
    },
)

internal data class RootChromeVisibility(
    val showTopBar: Boolean,
    val showBottomBar: Boolean,
    val preserveBottomBarExtent: Boolean,
)

/** Root top bar variants swapped via AnimatedContent (date header ↔ brand bar). */
private enum class RootHeaderKind { None, Context, Brand }

/** Push-style routes that own the full screen (chrome hidden); they slide vertically. */
private fun String?.isFullScreenPushRoute(): Boolean =
    this?.startsWith("timer") == true ||
        this == "search" ||
        this == "export" ||
        this == "calendar"

internal fun rootChromeVisibility(
    route: String?,
    logLayoutEditActive: Boolean,
): RootChromeVisibility {
    val routeOwnsFullScreen = route?.startsWith("timer") == true ||
        route == "search" ||
        route == "export" ||
        route == "calendar"
    val editorOwnsFullScreen = route == TopDest.Log.route && logLayoutEditActive
    val hideChrome = routeOwnsFullScreen || editorOwnsFullScreen
    val hasTopBar = route in setOf(
        TopDest.Log.route,
        TopDest.Summary.route,
        TopDest.Growth.route,
    ) || route == TopDest.Family.route || route?.startsWith(TopDest.Settings.route) == true
    return RootChromeVisibility(
        showTopBar = !hideChrome && hasTopBar,
        showBottomBar = !hideChrome,
        preserveBottomBarExtent = editorOwnsFullScreen,
    )
}

internal fun rootSnackbarBottomInset(
    route: String?,
    logLayoutEditActive: Boolean,
    elder: Boolean = false,
) = if (route == TopDest.Log.route && !logLayoutEditActive) {
    quickDockSnackbarBottomInset(elder)
} else {
    0.dp
}


@Composable
internal fun LeziRoot(
    vm: RootViewModel = hiltViewModel(),
    dark: Boolean = false,
    externalNavigationRequest: UntrustedExternalNavigation? = null,
    onExternalNavigationConsumed: (UntrustedExternalNavigation) -> Unit = {},
    pendingTimerFinishRequest: Boolean = false,
    onTimerFinishRequestConsumed: () -> Unit = {},
) {
    val forcedUpdate by vm.forcedAppUpdate.collectAsStateWithLifecycle()
    val recoveryExpanded by vm.forceShellSessionRecoveryExpanded.collectAsStateWithLifecycle()
    val recoveryState by vm.forcedUpdateSessionRecovery.collectAsStateWithLifecycle()
    val businessBlocked by rememberUpdatedState(forcedUpdate != null)
    val pendingUi by vm.ui.collectAsStateWithLifecycle()
    val pendingCreateBaby by vm.pendingCreateBaby.collectAsStateWithLifecycle()
    val ui = pendingUi
    // Force shell is above onboarding so a joined device still under baby setup cannot
    // silently miss PackageUnknown/WithPackage after client_update_required.
    val shellBaseMs = leziMotionMillis(LeziMotion.Base)
    val activity = LocalContext.current as? ComponentActivity
    val onboardingGraph = activity?.let { host ->
        viewModel(viewModelStoreOwner = host) { OnboardingGraphViewModel() }
    }
    val onboardingOwner = if (
        activity is HasDefaultViewModelProviderFactory &&
        onboardingGraph != null
    ) {
        remember(onboardingGraph, activity) {
            OnboardingGraphOwner(onboardingGraph, activity)
        }
    } else {
        null
    }
    Box(Modifier.fillMaxSize()) {
        RootBusinessWindowGate(
            blocked = forcedUpdate != null,
            recovering = recoveryExpanded && recoveryState.needsSessionRecovery,
            recovery = {
                // Recovery can authenticate only; business routes stay unmounted until force clears.
                if (onboardingOwner != null) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides onboardingOwner) {
                        OnboardingRoute(onFinished = {}, recoveryOnly = true)
                    }
                } else OnboardingRoute(onFinished = {}, recoveryOnly = true)
            },
        ) {

        if (ui == null) {
            // First emission pending: hold a themed blank frame instead of flashing
            // Onboarding off RootUi() defaults (motion-polish ticket 01).
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            )
        } else {
            Crossfade(
                targetState = onboardingGateTarget(ui, pendingCreateBaby),
                animationSpec = tween(durationMillis = shellBaseMs),
                label = "rootOnboardingGate",
            ) { showOnboarding ->
                if (showOnboarding == true && onboardingOwner != null && activity != null) {
                    // The hold keeps CreateBaby mounted after the family session lands.
                    // The graph store is activity-retained, so rotation keeps the wizard,
                    // and leaving the gate clears it so the next open starts clean.
                    CompositionLocalProvider(LocalViewModelStoreOwner provides onboardingOwner) {
                        DisposableEffect(onboardingOwner) {
                            onDispose {
                                if (clearOnboardingGraphOnDispose(activity.isChangingConfigurations || businessBlocked)) {
                                    onboardingGraph?.clearGraph()
                                }
                            }
                        }
                        OnboardingRoute(onFinished = {})
                    }
                } else if (showOnboarding == true) {
                    OnboardingRoute(onFinished = {})
                } else {
                    LeziMainScaffold(
                        vm = vm,
                        ui = ui,
                        dark = dark,
                        externalNavigationRequest = externalNavigationRequest,
                        onExternalNavigationConsumed = onExternalNavigationConsumed,
                        pendingTimerFinishRequest = pendingTimerFinishRequest,
                        onTimerFinishRequestConsumed = onTimerFinishRequestConsumed,
                    )
                }
            }
        }
        }
        RootForcedAppUpdateLayer(vm)
    }
}

/** Detach business windows as well as nodes; an in-tree overlay cannot cover Dialog windows. */
@Composable
internal fun RootBusinessWindowGate(
    blocked: Boolean,
    recovering: Boolean,
    recovery: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val retainedNavigation = rememberSaveableStateHolder()
    if (!blocked) {
        retainedNavigation.SaveableStateProvider("business") { content() }
    } else if (recovering) {
        recovery()
    }
}

@Composable
private fun RootForcedAppUpdateLayer(vm: RootViewModel) {
    val forcedUpdate by vm.forcedAppUpdate.collectAsStateWithLifecycle()
    val forcedBusy by vm.forcedUpdateBusy.collectAsStateWithLifecycle()
    val forcedMessage by vm.forcedUpdateMessage.collectAsStateWithLifecycle()
    val needsInstallPermission by vm.forcedUpdateNeedsInstallPermission.collectAsStateWithLifecycle()
    val sessionRecovery by vm.forcedUpdateSessionRecovery.collectAsStateWithLifecycle()
    val recoveryExpanded by vm.forceShellSessionRecoveryExpanded.collectAsStateWithLifecycle()
    // Auto-collapse recovery mode once the session is joined again so install returns.
    LaunchedEffect(sessionRecovery.needsSessionRecovery, forcedUpdate) {
        if (forcedUpdate != null && !sessionRecovery.needsSessionRecovery) {
            vm.collapseForceShellSessionRecovery()
        }
    }
    // Keep the last non-null shell so the exit fade still has content to animate.
    var lastForced by remember { mutableStateOf<ForcedAppUpdateState?>(null) }
    forcedUpdate?.let { lastForced = it }
    val showFullShell = forcedUpdate != null &&
        !(recoveryExpanded && sessionRecovery.needsSessionRecovery)
    val shellBaseMs = leziMotionMillis(LeziMotion.Base)
    val shellFastMs = leziMotionMillis(LeziMotion.Fast)
    AnimatedVisibility(
        visible = showFullShell,
        enter = fadeIn(animationSpec = tween(durationMillis = shellBaseMs)),
        // Exit side keeps the Fast value but rides the emphasized accelerate
        // leave curve (motion-polish ticket 04, post-token-02 supplement).
        exit = fadeOut(
            animationSpec = tween(
                durationMillis = shellFastMs,
                easing = LeziEasing.EmphasizedAccelerate,
            ),
        ),
        label = "forcedAppUpdateOverlay",
    ) {
        lastForced?.let { forced ->
            ForcedAppUpdateOverlay(
                forced = forced,
                busy = forcedBusy,
                message = forcedMessage,
                needsInstallPermission = needsInstallPermission,
                needsSessionRecovery = sessionRecovery.needsSessionRecovery,
                lanInviteApkUrl = sessionRecovery.lanInviteApkUrl,
                onInstall = { metadata -> vm.installForcedAppUpdate(metadata) },
                onRetryCheck = vm::retryForcedAppUpdateCheck,
                onRecoverSession = vm::expandForceShellSessionRecovery,
            )
        }
    }
    // Compact non-blocking banner while reauth/onboarding under the shell is usable.
    if (forcedUpdate != null && recoveryExpanded && sessionRecovery.needsSessionRecovery) {
        // Recovery is a bounded exception inside the forced shell. Back returns to that
        // shell rather than finishing the Activity that retains unsaved business drafts.
        // Business content stays detached because the force state itself is unchanged.
        BackHandler(onBack = vm::collapseForceShellSessionRecovery)
        ForcedAppUpdateRecoveryBanner(
            message = forcedMessage
                ?: "须更新乐记。请先完成重新登录，然后再回到强制更新安装。",
            onReturnToForceShell = vm::collapseForceShellSessionRecovery,
        )
    }
}

/** Session recovery + LAN invite facts for the force-update shell. */
data class ForcedUpdateSessionRecovery(
    val needsSessionRecovery: Boolean = false,
    val lanInviteApkUrl: String? = null,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LeziMainScaffold(
    vm: RootViewModel,
    ui: RootUi,
    dark: Boolean,
    externalNavigationRequest: UntrustedExternalNavigation?,
    onExternalNavigationConsumed: (UntrustedExternalNavigation) -> Unit,
    pendingTimerFinishRequest: Boolean = false,
    onTimerFinishRequestConsumed: () -> Unit = {},
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val today = ui.today
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var ephemeralViewRequest by remember {
        mutableStateOf<RecordComposerRequest.View?>(null)
    }
    val composerRequest = ephemeralViewRequest
        ?: restorableComposerRequest(ui.composerRequest)
    fun openComposerRequest(request: RecordComposerRequest) {
        when (request) {
            is RecordComposerRequest.View -> {
                vm.closeComposer()
                ephemeralViewRequest = request
            }
            else -> {
                ephemeralViewRequest = null
                vm.openComposer(request)
            }
        }
    }
    fun dismissComposer() {
        ephemeralViewRequest = null
        vm.closeComposer()
    }
    fun persistComposer() {
        ephemeralViewRequest = null
        vm.closeComposerAfterPersist()
    }
    val systemCalendarId by vm.systemCalendarId.collectAsStateWithLifecycle()
    val systemCalendarDisclosureLevel by vm.systemCalendarDisclosureLevel.collectAsStateWithLifecycle()
    var showHeaderCalendar by remember { mutableStateOf(false) }
    var showSystemCalendarSetup by rememberSaveable { mutableStateOf(false) }
    var displayedMonth by remember { mutableStateOf(YearMonth.from(ui.selectedDate)) }
    var logLayoutEditActive by remember { mutableStateOf(false) }
    var conflictOverlay by rememberSaveable(stateSaver = ConflictOverlayStateSaver) {
        mutableStateOf(ConflictOverlayState())
    }
    var pendingEditBabyId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Shell chrome / nav transitions — capture outside non-@Composable transitionSpec.
    val shellBaseMs = leziMotionMillis(LeziMotion.Base)
    val shellFastMs = leziMotionMillis(LeziMotion.Fast)
    // Full-screen push rides the Emphasized tier (motion-polish ticket 04).
    val shellEmphasizedMs = leziMotionMillis(LeziMotion.Emphasized)
    /** Durable accept token re-delivered to Composer Host after process death. */
    var acceptedTimerHandoffSeedJson by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    /** Monotonic reject / leave-before-accept signal for Host cancelTimerHandoff. */
    var timerHandoffRejectEpoch by rememberSaveable { mutableIntStateOf(0) }
    /**
     * Notification 「结束」 (ticket 12): open the timer page's completion form once.
     * Process-local only — the sheet itself is VM/durable-owned once opened.
     */
    var timerFinishOpenRequest by remember { mutableStateOf(false) }
    LaunchedEffect(pendingTimerFinishRequest) {
        if (!pendingTimerFinishRequest) return@LaunchedEffect
        // Consume before acting so repeated deliveries cannot replay the request.
        onTimerFinishRequestConsumed()
        if (nav.currentDestination?.route != "timer") {
            nav.navigate("timer")
        }
        timerFinishOpenRequest = true
    }

    externalNavigationRequest?.let { request ->
        UntrustedExternalNavigationConfirmationDialog(
            request = request,
            onDismiss = { onExternalNavigationConsumed(request) },
            onConfirm = confirm@{
                val authorized = authorizeExternalNavigation(
                    request = request,
                    userConfirmed = true,
                ) ?: return@confirm
                // Consume before any navigation so repeated taps cannot replay the request.
                onExternalNavigationConsumed(request)
                // Never replace an in-progress draft without its explicit discard action.
                if (composerRequest != null) return@confirm
                when (authorized) {
                    is AuthorizedExternalNavigation.WidgetComposer -> {
                        val target = authorized.target
                        if (ui.babies.none { it.id == target.babyId }) return@confirm
                        vm.openWidgetBaby(target.babyId)
                        vm.openComposer(
                            RecordComposerRequest.New(
                                babyId = target.babyId,
                                type = target.type,
                                timestamp = System.currentTimeMillis(),
                                historical = false,
                            ),
                        )
                    }
                    is AuthorizedExternalNavigation.Fulfill -> {
                        val target = authorized.target
                        vm.openExternalCarePlan(target.planId, target.clientUuid)
                    }
                }
            },
        )
    }
    val chrome = rootChromeVisibility(current, logLayoutEditActive)
    // Full-screen routes hide the root header; their own LeziDetailTopBar sits on
    // surface, so status-bar icons must follow that fill — not babyAccent.
    val statusBarFill = if (chrome.showTopBar) {
        leziTopBarBackground(dark)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val statusBarNeedsLightIcons = readableContentColor(statusBarFill) == Color.White
    val navigationScrim = MaterialTheme.colorScheme.surface.toArgb()
    val activity = LocalContext.current as ComponentActivity
    SideEffect {
        activity.enableEdgeToEdge(
            statusBarStyle = if (statusBarNeedsLightIcons) {
                SystemBarStyle.dark(Color.Transparent.toArgb())
            } else {
                SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb())
            },
            navigationBarStyle = if (dark) {
                SystemBarStyle.dark(navigationScrim)
            } else {
                SystemBarStyle.light(navigationScrim, navigationScrim)
            },
        )
    }
    val showContextHeader = current in setOf(
        TopDest.Log.route,
        TopDest.Summary.route,
        TopDest.Growth.route,
    )
    val showBrandHeader = current == TopDest.Family.route ||
        current?.startsWith(TopDest.Settings.route) == true
    val snackbarBottomInset = rootSnackbarBottomInset(
        current,
        logLayoutEditActive,
        elderModeEnabled(ui.elderMode),
    )


    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                vm.refreshToday()
                delay(millisUntilNextLocalMidnight(ZonedDateTime.now(ZoneId.systemDefault())))
            }
        }
    }

    Scaffold(
        modifier = Modifier.testTag(UiTags.ROOT),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .padding(bottom = snackbarBottomInset)
                    .testTag("root_snackbar_host"),
            )
        },
        topBar = {
            val headerKind = when {
                chrome.showTopBar && showContextHeader -> RootHeaderKind.Context
                chrome.showTopBar && showBrandHeader -> RootHeaderKind.Brand
                else -> RootHeaderKind.None
            }
            AnimatedContent(
                targetState = headerKind,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(durationMillis = shellBaseMs)) +
                        slideInVertically(
                            animationSpec = tween(durationMillis = shellBaseMs),
                            initialOffsetY = { height -> -height / 8 },
                        )).togetherWith(
                        fadeOut(animationSpec = tween(durationMillis = shellFastMs)),
                    )
                },
                label = "rootTopBar",
            ) { header ->
                when (header) {
                    RootHeaderKind.Context -> {
                        val shallowSyncLine by vm.shallowSyncLine.collectAsStateWithLifecycle()
                        LeziTopBarContainer(dark = dark) {
                            AppHeaderBar(
                                babyName = ui.baby?.nickname.orEmpty(),
                                babyAge = ui.baby?.let { babyAgeShortLabel(it.birthdayEpochDay) }.orEmpty(),
                                avatarPath = ui.baby?.avatarPath,
                                sleeping = ui.sleeping,
                                selectedDate = ui.selectedDate,
                                today = today,
                                canCycleBaby = ui.babies.size > 1,
                                canGoNext = ui.selectedDate.isBefore(today),
                                dark = dark,
                                onCycleBaby = { vm.cycleBaby() },
                                onJumpSiblingSameDayAge = { vm.jumpSiblingSameDayAge() },
                                onPreviousDate = { vm.shiftDay(-1) },
                                onNextDate = { vm.shiftDay(1) },
                                onOpenDatePicker = {
                                    displayedMonth = YearMonth.from(ui.selectedDate)
                                    vm.setCalendarMonth(displayedMonth)
                                    showHeaderCalendar = true
                                },
                                onSearch = { nav.navigate("search") },
                                collapsedSyncText = if (
                                    current in setOf(
                                        TopDest.Log.route,
                                        TopDest.Summary.route,
                                        TopDest.Growth.route,
                                    ) && shallowSyncLine.text.isNotBlank()
                                ) {
                                    shallowSyncLine.text
                                } else {
                                    null
                                },
                                collapsedSyncIsError = shallowSyncLine.isError,
                            )
                        }
                    }
                    RootHeaderKind.Brand -> {
                        LeziTopBarContainer(dark = dark) {
                            AppBrandBar(
                                onToggleTheme = { vm.toggleDark() },
                                dark = dark,
                            )
                        }
                    }
                    RootHeaderKind.None -> Unit
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = chrome.showBottomBar,
                enter = slideInVertically(
                    animationSpec = tween(durationMillis = shellBaseMs),
                    initialOffsetY = { height -> height },
                ) + fadeIn(animationSpec = tween(durationMillis = shellBaseMs)),
                exit = slideOutVertically(
                    animationSpec = tween(durationMillis = shellBaseMs),
                    targetOffsetY = { height -> height },
                ) + fadeOut(animationSpec = tween(durationMillis = shellFastMs)),
            ) {
                val sky = com.lezi.babylog.designsystem.LeziThemeExt.colors.skySoft
                // Dark skySoft is nearly the same luminance as DarkSurface, so the
                // selected indicator disappears; use a translucent primary instead.
                val indicatorColor = if (dark) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
                } else {
                    sky
                }
                // Bottom long-press baby-cycle confirm haptic (0.5.4 ticket 15, spec §M3).
                val haptics = rememberLeziHaptics()
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    TopDest.entries.forEach { dest ->
                        val selected = current == dest.route ||
                            (dest == TopDest.Log && current?.startsWith("log") == true) ||
                            (dest == TopDest.Settings && current?.startsWith("settings") == true)
                        val navigateToDestination = {
                            nav.navigate(dest.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                        val onLongClick = if (dest == TopDest.Log ||
                            dest == TopDest.Summary ||
                            dest == TopDest.Growth
                        ) {
                            // Gate mirrors header canCycleBaby: no haptic or call on a
                            // single-baby long-press no-op (bottomNavBabyCycleClick).
                            bottomNavBabyCycleClick(
                                canCycle = ui.babies.size > 1,
                                haptics = haptics,
                                cycleBaby = vm::cycleBaby,
                            )
                        } else {
                            null
                        }
                        NavigationBarItem(
                            selected = selected,
                            // Short-press navigation owner: Material onClick only.
                            // Long-press baby cycle: [bottomNavLongPressOnly] (onLongPress,
                            // never onTap) so short press cannot double-fire.
                            onClick = { navigateToDestination() },
                            modifier = Modifier.bottomNavLongPressOnly(
                                onLongClick = onLongClick,
                                restartKey = ui.babies.size > 1,
                            ),
                            icon = {
                                Icon(
                                    if (selected) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = dest.label,
                                )
                            },
                            label = { Text(dest.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = indicatorColor,
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
            if (!chrome.showBottomBar && chrome.preserveBottomBarExtent) {
                // Keep the editor Dock aligned with its everyday position without
                // exposing a second copy of the primary navigation tabs.
                NavigationBar(
                    modifier = Modifier.testTag("layout_edit_primary_nav_reserved"),
                    containerColor = MaterialTheme.colorScheme.background,
                    tonalElevation = 0.dp,
                ) {}
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = rootStartDestination(ui.hasBaby, ui.familyRole),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
            enterTransition = {
                // Full-screen push enters on the Emphasized tier with the
                // emphasized decelerate reveal curve (motion-polish ticket 04);
                // tab switches keep the plain Fast content fade.
                if (targetState.destination.route.isFullScreenPushRoute()) {
                    slideInVertically(
                        animationSpec = tween(
                            durationMillis = shellEmphasizedMs,
                            easing = LeziEasing.EmphasizedDecelerate,
                        ),
                        initialOffsetY = { height -> height / 24 },
                    ) + fadeIn(
                        animationSpec = tween(
                            durationMillis = shellEmphasizedMs,
                            easing = LeziEasing.EmphasizedDecelerate,
                        ),
                    )
                } else {
                    fadeIn(animationSpec = tween(durationMillis = shellFastMs))
                }
            },
            exitTransition = {
                // The screen a push covers leaves on a Fast accelerate fade;
                // tab-switch exits keep the plain Fast fade.
                if (targetState.destination.route.isFullScreenPushRoute()) {
                    fadeOut(
                        animationSpec = tween(
                            durationMillis = shellFastMs,
                            easing = LeziEasing.EmphasizedAccelerate,
                        ),
                    )
                } else {
                    fadeOut(animationSpec = tween(durationMillis = shellFastMs))
                }
            },
            popEnterTransition = { fadeIn(animationSpec = tween(durationMillis = shellFastMs)) },
            popExitTransition = {
                // Pop mirrors the push pair: the full-screen route settles back
                // on Emphasized decelerate; everything else keeps the plain
                // Fast fade.
                if (initialState.destination.route.isFullScreenPushRoute()) {
                    slideOutVertically(
                        animationSpec = tween(
                            durationMillis = shellEmphasizedMs,
                            easing = LeziEasing.EmphasizedDecelerate,
                        ),
                        targetOffsetY = { height -> height / 24 },
                    ) + fadeOut(
                        animationSpec = tween(
                            durationMillis = shellEmphasizedMs,
                            easing = LeziEasing.EmphasizedDecelerate,
                        ),
                    )
                } else {
                    fadeOut(animationSpec = tween(durationMillis = shellFastMs))
                }
            },
        ) {
            composable(TopDest.Log.route) {
                LogRoute(
                    externalDay = ui.selectedDate,
                    onOpenComposer = ::openComposerRequest,
                    onGoToday = vm::goToday,
                    onSelectedDayChange = vm::setDay,
                    onLayoutEditModeChanged = { active ->
                        logLayoutEditActive = active
                    },
                    onMessage = { message ->
                        scope.launch { snackbar.showSnackbar(message) }
                    },
                    onOpenConflictResolver = { conflictOverlay = conflictOverlay.openResolver(it) },
                )
            }
            composable(TopDest.Summary.route) { SummaryRoute(anchorDate = ui.selectedDate) }
            composable(TopDest.Growth.route) { GrowthRoute(initialDate = ui.selectedDate) }
            composable(TopDest.Family.route) {
                FamilyRoute(
                    onOpenConflictInbox = { conflictOverlay = conflictOverlay.openInbox() },
                    pendingEditBabyId = pendingEditBabyId,
                    onPendingEditBabyConsumed = { pendingEditBabyId = null },
                )
            }
            composable(TopDest.Settings.route) {
                SettingsRoute(
                    onOpenExport = { nav.navigate("export") },
                    onOpenSearch = { nav.navigate("search") },
                    onOpenCalendar = { nav.navigate("calendar") },
                )
            }
            composable("search") {
                SearchRoute(
                    onBack = { nav.popBackStack() },
                    onOpenRecord = { open ->
                        openComposerRequest(
                            recordComposerOpenRequest(
                                recordId = open.recordId,
                                canEdit = open.canEdit,
                            ),
                        )
                    },
                )
            }
            composable("export") {
                ExportRoute(onBack = { nav.popBackStack() })
            }
            composable("calendar") {
                CalendarRoute(
                    onBack = { nav.popBackStack() },
                    initialDate = ui.selectedDate,
                    onScheduleCare = { type, scheduledAt, customItemId ->
                        val babyId = ui.baby?.id ?: return@CalendarRoute
                        vm.openComposer(
                            RecordComposerRequest.New(
                                babyId = babyId,
                                type = type,
                                timestamp = scheduledAt,
                                historical = false,
                                customItemId = customItemId,
                                createIntent = ComposerCreateIntent.ScheduleCare,
                            ),
                        )
                    },
                    onFulfillPlan = { planId ->
                        vm.openComposer(RecordComposerRequest.Fulfill(planId))
                    },
                    onEditPlan = { planId ->
                        vm.openComposer(RecordComposerRequest.EditPlan(planId))
                    },
                )
            }
            composable("timer") {
                val source = nav.previousBackStackEntry?.savedStateHandle
                val handoffSeed = TimerHandoffSeed.fromJson(
                    source?.get<String>(TIMER_HANDOFF_SEED_JSON_KEY),
                )
                TimerRoute(
                    initialNote = handoffSeed?.note.orEmpty(),
                    initialAmountMl = handoffSeed?.amountMl.orEmpty(),
                    carePlanId = handoffSeed?.carePlanId,
                    babyId = handoffSeed?.babyId,
                    handoffSeed = handoffSeed,
                    autoOpenCompletion = timerFinishOpenRequest,
                    onAutoOpenCompletionConsumed = { timerFinishOpenRequest = false },
                    onHandoffAccepted = { seed ->
                        // Timer owns the seed. Durable token drives Host release even when
                        // process death already dropped the in-memory route (AlreadyAccepted path).
                        acceptedTimerHandoffSeedJson = seed.toJson()
                        source?.remove<String>(TIMER_HANDOFF_SEED_JSON_KEY)
                    },
                    onHandoffRejected = {
                        // Keep Composer draft + photos editable; leave timer route.
                        timerHandoffRejectEpoch += 1
                        source?.remove<String>(TIMER_HANDOFF_SEED_JSON_KEY)
                        scope.launch {
                            snackbar.showSnackbar("当前已有进行中的计时，草稿仍可编辑")
                        }
                        nav.popBackStack()
                    },
                    onLeaveRunning = {
                        timerHandoffRejectEpoch += 1
                        source?.remove<String>(TIMER_HANDOFF_SEED_JSON_KEY)
                        nav.popBackStack()
                        scope.launch {
                            snackbar.showSnackbar("计时仍在后台继续")
                        }
                    },
                    onLeavePaused = {
                        timerHandoffRejectEpoch += 1
                        source?.remove<String>(TIMER_HANDOFF_SEED_JSON_KEY)
                        nav.popBackStack()
                        scope.launch {
                            snackbar.showSnackbar("计时已暂停，稍后可从喂奶计时继续")
                        }
                    },
                    onDone = {
                        // Pop / discard before accept: unlock Composer without releasing files.
                        timerHandoffRejectEpoch += 1
                        source?.remove<String>(TIMER_HANDOFF_SEED_JSON_KEY)
                        nav.popBackStack()
                    },
                )
            }
        }
    }

    // Drop orphan accept token if Composer root is already gone (e.g. double-deliver).
    LaunchedEffect(composerRequest, acceptedTimerHandoffSeedJson) {
        if (composerRequest == null && acceptedTimerHandoffSeedJson != null) {
            acceptedTimerHandoffSeedJson = null
        }
    }

    RecordComposerHost(
        request = composerRequest,
        onDismiss = ::dismissComposer,
        onPersisted = ::persistComposer,
        onSaved = { message ->
            scope.launch { snackbar.showSnackbar(message) }
        },
        onStartNursingTimer = { session ->
            // Ownership transfer: do NOT close Composer until Timer accepts seed.
            // Sole navigate payload is seed JSON; accept/reject settle via saveable tokens.
            nav.currentBackStackEntry?.savedStateHandle?.set(
                TIMER_HANDOFF_SEED_JSON_KEY,
                session.seed.toJson(),
            )
            nav.navigate("timer")
        },
        acceptedTimerHandoffSeedJson = acceptedTimerHandoffSeedJson,
        onAcceptedTimerHandoffConsumed = { acceptedTimerHandoffSeedJson = null },
        timerHandoffRejectEpoch = timerHandoffRejectEpoch,
        // Ticket 21: unconfigured plan switch → explicit setup; dismiss still allows save.
        onConfigureSystemCalendar = { showSystemCalendarSetup = true },
    )

    if (showSystemCalendarSetup) {
        val calendarCommandState by vm.calendarSetupState.collectAsStateWithLifecycle()
        SystemCalendarSetupDialog(
            currentCalendarId = systemCalendarId,
            currentDisclosureLevel = systemCalendarDisclosureLevel,
            commandState = calendarCommandState,
            onConfirm = { selection ->
                vm.confirmSystemCalendar(selection.calendarId, selection.disclosureLevel)
            },
            onDisable = { vm.disableSystemCalendar() },
            onDismiss = {
                if (!calendarCommandState.busy) {
                    vm.consumeCalendarSetupResult()
                    showSystemCalendarSetup = false
                }
            },
        )
    }

    if (showHeaderCalendar) {
        val recordDays by vm.calendarRecordDays.collectAsStateWithLifecycle()
        HeaderCalendarDialog(
            selectedDate = ui.selectedDate,
            displayedMonth = displayedMonth,
            today = today,
            recordDays = recordDays,
            onMonthChange = { requested ->
                displayedMonth = if (requested > YearMonth.from(today)) {
                    YearMonth.from(today)
                } else {
                    requested
                }
                vm.setCalendarMonth(displayedMonth)
            },
            onSelect = { selected ->
                vm.setDay(selected)
                showHeaderCalendar = false
            },
            onGoToday = {
                vm.goToday()
                showHeaderCalendar = false
            },
            onDismiss = { showHeaderCalendar = false },
        )
    }

    if (conflictOverlay.inboxVisible) {
        ConflictInboxRoute(
            onDismiss = { conflictOverlay = conflictOverlay.dismissInbox() },
            onOpenConflict = { conflictId ->
                conflictOverlay = conflictOverlay.openResolver(conflictId)
            },
        )
    }

    conflictOverlay.resolverConflictId?.let { conflictId ->
        if (UnresolvedInboxIds.isLocal(conflictId)) {
            LocalUnresolvedResolverRoute(
                inboxId = conflictId,
                onDismiss = { conflictOverlay = conflictOverlay.dismissResolver() },
            )
        } else {
        ConflictResolverRoute(
            conflictId = conflictId,
            onDismiss = { conflictOverlay = conflictOverlay.dismissResolver() },
            onResolved = { edit ->
                conflictOverlay = conflictOverlay.dismissResolver()
                when (edit) {
                    is ConflictEditTarget.Record ->
                        openComposerRequest(RecordComposerRequest.Edit(edit.recordId))
                    is ConflictEditTarget.CarePlan ->
                        openComposerRequest(RecordComposerRequest.EditPlan(edit.carePlanId))
                    is ConflictEditTarget.Baby -> {
                        pendingEditBabyId = edit.babyId
                        nav.navigate(TopDest.Family.route) {
                            launchSingleTop = true
                        }
                    }
                    null -> scope.launch { snackbar.showSnackbar("冲突已解决") }
                }
                if (edit != null) {
                    scope.launch { snackbar.showSnackbar("冲突已解决，继续修改") }
                }
            },
        )
        }
    }
}

/**
 * Full-screen, non-dismissible force-update gate (no "稍后").
 * Covers the whole activity content so log/summary/account cannot be used to bypass
 * main features. Session recovery is an intentional exception: when credentials are
 * gone, the shell offers re-login (temporarily lowers the sink) and LAN invite-install
 * guidance on port 8767 without clearing the force state.
 * System back is consumed and the surface sinks pointer events so taps cannot reach
 * the scaffold or onboarding underneath (except after explicit recovery expand).
 */
@Composable
private fun ForcedAppUpdateOverlay(
    forced: ForcedAppUpdateState,
    busy: Boolean,
    message: String?,
    needsInstallPermission: Boolean,
    needsSessionRecovery: Boolean,
    lanInviteApkUrl: String?,
    onInstall: (AppUpdateMetadata) -> Unit,
    onRetryCheck: () -> Unit,
    onRecoverSession: () -> Unit,
) {
    val context = LocalContext.current
    // Consume system back while forced — no "稍后" and no back-to-main bypass.
    BackHandler(enabled = true) { }
    val body = when (forced) {
        is ForcedAppUpdateState.WithPackage -> forcedUpdateDialogBody(forced.metadata)
        ForcedAppUpdateState.PackageUnknown -> forcedUpdatePackageUnknownBody()
    }
    val sinkInteraction = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .fillMaxSize()
            // Absorb taps so main tabs / onboarding under the mask cannot be used.
            .clickable(
                interactionSource = sinkInteraction,
                indication = null,
                onClick = {},
            )
            .semantics { contentDescription = "强制更新乐记" }
            .testTag("forced_app_update_overlay"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(LeziSpacing.Lg),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                forcedUpdateTitle(),
                style = LeziThemeExt.typography.Title,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(LeziSpacing.Md))
            Text(
                body,
                style = LeziTypography.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(LeziSpacing.Md))
                Text(
                    message,
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (lanInviteApkUrl != null &&
                (forced is ForcedAppUpdateState.PackageUnknown || needsSessionRecovery)
            ) {
                Spacer(Modifier.height(LeziSpacing.Md))
                Text(
                    forcedUpdateLanInviteGuidance(lanInviteApkUrl),
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(LeziSpacing.Xl))
            when (forced) {
                is ForcedAppUpdateState.WithPackage -> {
                    LeziPrimaryButton(
                        label = if (busy) "安装中…" else "立即更新",
                        onClick = { onInstall(forced.metadata) },
                        enabled = !busy,
                        busy = busy,
                    )
                    Spacer(Modifier.height(LeziSpacing.Sm))
                    LeziTextButton(
                        label = forcedUpdateRetryCheckLabel(),
                        onClick = onRetryCheck,
                        enabled = !busy,
                    )
                }
                ForcedAppUpdateState.PackageUnknown -> {
                    LeziPrimaryButton(
                        label = if (busy) "检查中…" else forcedUpdateRetryCheckLabel(),
                        onClick = onRetryCheck,
                        enabled = !busy,
                        busy = busy,
                    )
                }
            }
            if (needsSessionRecovery) {
                Spacer(Modifier.height(LeziSpacing.Sm))
                LeziTextButton(
                    label = forcedUpdateSessionRecoveryLabel(),
                    onClick = onRecoverSession,
                    tone = LeziTextButtonTone.Primary,
                )
            }
            if (lanInviteApkUrl != null) {
                Spacer(Modifier.height(LeziSpacing.Sm))
                LeziTextButton(
                    label = forcedUpdateLanInviteOpenLabel(),
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(lanInviteApkUrl))
                        runCatching { context.startActivity(intent) }
                    },
                )
            }
            if (needsInstallPermission) {
                Spacer(Modifier.height(LeziSpacing.Sm))
                LeziTextButton(
                    label = "去设置",
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}"),
                        )
                        runCatching { context.startActivity(intent) }
                    },
                    tone = LeziTextButtonTone.Primary,
                )
            }
        }
    }
}

/** Non-blocking banner while force state is retained and session recovery UI is usable. */
@Composable
private fun ForcedAppUpdateRecoveryBanner(
    message: String,
    onReturnToForceShell: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .semantics { contentDescription = "强制更新会话恢复" }
            .testTag("forced_app_update_recovery_banner"),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(LeziSpacing.Md),
        ) {
            Text(
                forcedUpdateTitle(),
                style = LeziTypography.BodyStrong,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(LeziSpacing.Xs))
            Text(
                message,
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(LeziSpacing.Sm))
            LeziTextButton(
                label = "返回强制更新",
                onClick = onReturnToForceShell,
                tone = LeziTextButtonTone.Primary,
            )
        }
    }
}

internal fun shouldShowOnboarding(
    hasBaby: Boolean,
    familyRole: FamilyRole,
    pendingCreateBaby: Boolean = false,
): Boolean = !hasBaby && (familyRole == FamilyRole.None || pendingCreateBaby)

/**
 * Onboarding gate target for the root Crossfade; null while [RootViewModel.ui] is
 * unresolved, in which case the root holds a themed blank frame instead of rendering
 * Onboarding off default values (motion-polish ticket 01).
 *
 * [pendingCreateBaby] keeps the wizard mounted for an unconsumed CreateBaby step
 * after the family session has already become Owner. A cold start does not set it.
 */
internal fun onboardingGateTarget(ui: RootUi?, pendingCreateBaby: Boolean = false): Boolean? =
    ui?.let { shouldShowOnboarding(it.hasBaby, it.familyRole, pendingCreateBaby) }

/** Joined devices with no baby land on the account tab, where a baby can be created. */
internal fun rootStartDestination(hasBaby: Boolean, familyRole: FamilyRole): String =
    if (!hasBaby && familyRole != FamilyRole.None) {
        TopDest.Family.route
    } else {
        TopDest.Log.route
    }

/**
 * Dark-theme resolution shared by the Ready branch, the local-data gate screen, and
 * the launch-window palette: the DataStore setting wins, "system" follows the OS.
 */
internal fun leziDarkTheme(darkMode: String, systemDark: Boolean): Boolean = when (darkMode) {
    "dark" -> true
    "light" -> false
    else -> systemDark
}

/** Sole Composer→Timer navigate payload (Ticket 09). */
private const val TIMER_HANDOFF_SEED_JSON_KEY = "timer_handoff_seed_json"
