package com.lezi.babylog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.designsystem.AppBrandBar
import com.lezi.babylog.designsystem.LeziColors
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.babyAgeLabel
import com.lezi.babylog.feature.export.ExportRoute
import com.lezi.babylog.feature.family.FamilyRoute
import com.lezi.babylog.feature.growth.GrowthRoute
import com.lezi.babylog.feature.log.LogRoute
import com.lezi.babylog.feature.log.RecordComposerHost
import com.lezi.babylog.feature.log.RecordComposerRequest
import com.lezi.babylog.feature.onboarding.OnboardingRoute
import com.lezi.babylog.feature.search.SearchRoute
import com.lezi.babylog.feature.settings.CalendarRoute
import com.lezi.babylog.feature.settings.SettingsRoute
import com.lezi.babylog.feature.summary.SummaryRoute
import com.lezi.babylog.feature.timer.TimerRoute
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: RootViewModel = hiltViewModel()
            val ui by vm.ui.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = when (ui.darkMode) {
                "dark" -> true
                "light" -> false
                else -> systemDark
            }
            LeziTheme(
                darkTheme = dark,
                babyThemeArgb = ui.baby?.themeColorArgb,
                visualStyle = ui.visualStyle,
            ) {
                val transparent = Color.Transparent.toArgb()
                val navigationScrim = MaterialTheme.colorScheme.surface.toArgb()
                SideEffect {
                    this@MainActivity.enableEdgeToEdge(
                        statusBarStyle = if (dark) {
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
                LeziRoot(vm = vm, dark = dark)
            }
        }
    }
}

data class RootUi(
    val hasBaby: Boolean = false,
    val baby: Baby? = null,
    val babies: List<Baby> = emptyList(),
    val sleeping: Boolean = false,
    val darkMode: String = "system",
    val visualStyle: String = "warm",
    val selectedDate: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val calendarRecordDays: Set<LocalDate> = emptySet(),
)

@HiltViewModel
class RootViewModel @Inject constructor(
    private val careLog: CareLog,
    private val settings: SettingsStore,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val dayFlow = MutableStateFlow(
        clampSelectedDate(
            savedStateHandle.get<Long>(SELECTED_DATE_KEY)?.let(LocalDate::ofEpochDay)
                ?: LocalDate.now(),
        ),
    )
    private val calendarMonthFlow = MutableStateFlow(YearMonth.from(dayFlow.value))
    private val zone = ZoneId.systemDefault()
    private val todayFlow = MutableStateFlow(LocalDate.now(zone))

    init {
        savedStateHandle[SELECTED_DATE_KEY] = dayFlow.value.toEpochDay()
        viewModelScope.launch {
            careLog.ensureCurrentBabyHealed()
        }
    }

    private val baseUi = combine(
        careLog.observeHasBaby(),
        careLog.observeCurrentBaby(),
        careLog.observeBabies(),
        settings.settings,
        dayFlow,
    ) { has, current, babies, s, day ->
        RootUi(
            hasBaby = has,
            baby = current,
            babies = babies,
            darkMode = s.darkMode,
            visualStyle = s.visualStyle,
            selectedDate = day,
        )
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private val calendarRecordDays = combine(
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
            }
        }
    }

    val ui = combine(
        baseUi,
        sleepingBaby,
        calendarRecordDays,
        todayFlow,
    ) { base, (sleepingBabyId, sleeping), recordDays, today ->
        base.copy(
            sleeping = sleepingBabyId == base.baby?.id && sleeping,
            calendarRecordDays = recordDays,
            today = today,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RootUi())

    fun cycleBaby() {
        viewModelScope.launch {
            val state = ui.value
            if (state.babies.size < 2) return@launch
            val cur = state.baby?.id
            val idx = state.babies.indexOfFirst { it.id == cur }.takeIf { it >= 0 } ?: 0
            val next = state.babies[(idx + 1) % state.babies.size]
            careLog.setCurrentBaby(next.id)
        }
    }

    fun toggleDark() {
        viewModelScope.launch {
            val next = when (ui.value.darkMode) {
                "dark" -> "light"
                else -> "dark"
            }
            settings.setDarkMode(next)
        }
    }

    fun shiftDay(delta: Long) {
        updateSelectedDate(dayFlow.value.plusDays(delta))
    }

    fun setDay(day: LocalDate) {
        updateSelectedDate(day)
    }

    fun setCalendarMonth(month: YearMonth) {
        val currentMonth = YearMonth.from(todayFlow.value)
        calendarMonthFlow.value = if (month > currentMonth) currentMonth else month
    }

    fun refreshToday() {
        val current = LocalDate.now(zone)
        val previous = todayFlow.value
        if (current == previous) return
        todayFlow.value = current
        if (dayFlow.value == previous) {
            updateSelectedDate(current)
        }
    }

    private fun updateSelectedDate(day: LocalDate) {
        val selected = clampSelectedDate(day, todayFlow.value)
        dayFlow.value = selected
        savedStateHandle[SELECTED_DATE_KEY] = selected.toEpochDay()
    }

    private companion object {
        const val SELECTED_DATE_KEY = "root_selected_date_epoch_day"
    }
}

internal fun clampSelectedDate(
    requested: LocalDate,
    today: LocalDate = LocalDate.now(),
): LocalDate = if (requested.isAfter(today)) today else requested

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

@Composable
fun LeziRoot(
    vm: RootViewModel = hiltViewModel(),
    dark: Boolean = false,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    if (!ui.hasBaby) {
        OnboardingRoute(onFinished = { /* CareLog flow updates */ })
        return
    }
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val today = ui.today
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var composerRequest by remember { mutableStateOf<RecordComposerRequest?>(null) }
    var showHeaderCalendar by remember { mutableStateOf(false) }
    var displayedMonth by remember { mutableStateOf(YearMonth.from(ui.selectedDate)) }
    val hideChrome = current?.startsWith("timer") == true ||
        current == "search" ||
        current == "export" ||
        current == "calendar"
    val showContextHeader = current in setOf(
        TopDest.Log.route,
        TopDest.Summary.route,
        TopDest.Growth.route,
    )
    val showBrandHeader = current in setOf(
        TopDest.Family.route,
        TopDest.Settings.route,
    )

    LaunchedEffect(today) {
        vm.refreshToday()
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        val waitMillis = Duration.between(now, nextMidnight)
            .toMillis()
            .coerceAtLeast(1_000L)
        delay(waitMillis)
        vm.refreshToday()
    }

    Scaffold(
        modifier = Modifier.testTag(UiTags.ROOT),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            when {
                !hideChrome && showContextHeader -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (dark) {
                                    MaterialTheme.colorScheme.surface
                                } else {
                                    LeziColors.JournalAccent
                                },
                            )
                            .statusBarsPadding(),
                    ) {
                        AppHeaderBar(
                            babyName = ui.baby?.nickname.orEmpty(),
                            babyAge = ui.baby?.let { babyAgeLabel(it.birthdayEpochDay) }.orEmpty(),
                            avatarPath = ui.baby?.avatarPath,
                            sleeping = ui.sleeping,
                            selectedDate = ui.selectedDate,
                            today = today,
                            canCycleBaby = ui.babies.size > 1,
                            canGoNext = ui.selectedDate.isBefore(today),
                            dark = dark,
                            onCycleBaby = { vm.cycleBaby() },
                            onPreviousDate = { vm.shiftDay(-1) },
                            onNextDate = { vm.shiftDay(1) },
                            onOpenDatePicker = {
                                displayedMonth = YearMonth.from(ui.selectedDate)
                                vm.setCalendarMonth(displayedMonth)
                                showHeaderCalendar = true
                            },
                            onSearch = { nav.navigate("search") },
                        )
                    }
                }
                !hideChrome && showBrandHeader -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (dark) {
                                    MaterialTheme.colorScheme.surface
                                } else {
                                    LeziColors.JournalAccent
                                },
                            )
                            .statusBarsPadding(),
                    ) {
                        AppBrandBar(
                            onToggleTheme = { vm.toggleDark() },
                            dark = dark,
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (!hideChrome) {
                val sky = com.lezi.babylog.designsystem.LeziThemeExt.colors.skySoft
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    tonalElevation = 0.dp,
                ) {
                    TopDest.entries.forEach { dest ->
                        val selected = current == dest.route ||
                            (dest == TopDest.Log && current?.startsWith("log") == true)
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(dest.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Box(
                                    Modifier.pointerInput(dest) {
                                        detectTapGestures(
                                            onLongPress = {
                                                if (dest == TopDest.Log ||
                                                    dest == TopDest.Summary ||
                                                    dest == TopDest.Growth
                                                ) {
                                                    vm.cycleBaby()
                                                }
                                            },
                                            onTap = {
                                                nav.navigate(dest.route) {
                                                    popUpTo(nav.graph.findStartDestination().id) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            },
                                        )
                                    },
                                ) {
                                    Icon(
                                        if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label,
                                    )
                                }
                            },
                            label = { Text(dest.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = sky,
                                selectedIconColor = MaterialTheme.colorScheme.onBackground,
                                selectedTextColor = MaterialTheme.colorScheme.onBackground,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = TopDest.Log.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(TopDest.Log.route) {
                LogRoute(
                    externalDay = ui.selectedDate,
                    onOpenComposer = { composerRequest = it },
                    onGoToday = { vm.setDay(today) },
                )
            }
            composable(TopDest.Summary.route) { SummaryRoute(anchorDate = ui.selectedDate) }
            composable(TopDest.Growth.route) { GrowthRoute(initialDate = ui.selectedDate) }
            composable(TopDest.Family.route) { FamilyRoute() }
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
                    onOpenEdit = { id ->
                        composerRequest = RecordComposerRequest.Edit(id)
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
                )
            }
            composable("timer") {
                val source = nav.previousBackStackEntry?.savedStateHandle
                TimerRoute(
                    initialNote = source?.get<String>(TIMER_SEED_NOTE_KEY).orEmpty(),
                    initialAmountMl = source?.get<String>(TIMER_SEED_AMOUNT_KEY).orEmpty(),
                    onDone = { nav.popBackStack() },
                )
            }
        }
    }

    RecordComposerHost(
        request = composerRequest,
        onDismiss = { composerRequest = null },
        onSaved = { message ->
            composerRequest = null
            scope.launch { snackbar.showSnackbar(message) }
        },
        onStartNursingTimer = { note, amountMl ->
            composerRequest = null
            nav.currentBackStackEntry?.savedStateHandle?.apply {
                set(TIMER_SEED_NOTE_KEY, note)
                set(TIMER_SEED_AMOUNT_KEY, amountMl)
            }
            nav.navigate("timer")
        },
    )

    if (showHeaderCalendar) {
        HeaderCalendarDialog(
            selectedDate = ui.selectedDate,
            displayedMonth = displayedMonth,
            today = today,
            recordDays = ui.calendarRecordDays,
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
            onDismiss = { showHeaderCalendar = false },
        )
    }
}

private const val TIMER_SEED_NOTE_KEY = "timer_seed_note"
private const val TIMER_SEED_AMOUNT_KEY = "timer_seed_amount_ml"
