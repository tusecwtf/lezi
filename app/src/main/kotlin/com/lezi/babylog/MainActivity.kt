package com.lezi.babylog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.designsystem.AppBrandBar
import com.lezi.babylog.designsystem.AppContextRow
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.feature.export.ExportRoute
import com.lezi.babylog.feature.family.FamilyRoute
import com.lezi.babylog.feature.growth.GrowthRoute
import com.lezi.babylog.feature.log.LogRoute
import com.lezi.babylog.feature.log.RecordEditRoute
import com.lezi.babylog.feature.onboarding.OnboardingRoute
import com.lezi.babylog.feature.search.SearchRoute
import com.lezi.babylog.feature.settings.CalendarRoute
import com.lezi.babylog.feature.settings.SettingsRoute
import com.lezi.babylog.feature.summary.SummaryRoute
import com.lezi.babylog.feature.timer.TimerRoute
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
                LeziRoot(vm = vm, dark = dark)
            }
        }
    }
}

data class RootUi(
    val hasBaby: Boolean = false,
    val baby: Baby? = null,
    val babies: List<Baby> = emptyList(),
    val darkMode: String = "system",
    val visualStyle: String = "warm",
    val day: LocalDate = LocalDate.now(),
)

@HiltViewModel
class RootViewModel @Inject constructor(
    private val careLog: CareLog,
    private val settings: SettingsStore,
) : ViewModel() {
    private val dayFlow = MutableStateFlow(LocalDate.now())

    init {
        viewModelScope.launch {
            careLog.ensureCurrentBabyHealed()
        }
    }

    val ui = combine(
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
            day = day,
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

    fun shiftDay(delta: Long) {
        val today = LocalDate.now()
        val next = dayFlow.value.plusDays(delta)
        dayFlow.value = if (next.isAfter(today)) today else next
    }

    fun setDay(day: LocalDate) {
        val today = LocalDate.now()
        dayFlow.value = if (day.isAfter(today)) today else day
    }

    fun toggleDark() {
        viewModelScope.launch {
            val cur = ui.value.darkMode
            val next = when (cur) {
                "dark" -> "light"
                else -> "dark"
            }
            settings.setDarkMode(next)
        }
    }
}

private enum class TopDest(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Log("log", "记录", Icons.Filled.GridView, Icons.Outlined.GridView),
    Summary("summary", "汇总", Icons.Filled.BarChart, Icons.Outlined.BarChart),
    Growth("growth", "成长", Icons.Filled.ShowChart, Icons.Outlined.ShowChart),
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
    val today = LocalDate.now()
    val dayLabel = rememberDayLabel(ui.day, today)
    val accent = ui.baby?.themeColorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val hideChrome = current?.startsWith("timer") == true ||
        current?.startsWith("edit") == true ||
        current == "search" ||
        current == "export" ||
        current == "calendar"

    Scaffold(
        modifier = Modifier.testTag(UiTags.ROOT),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (!hideChrome) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding(),
                ) {
                    AppBrandBar(
                        onSearch = { nav.navigate("search") },
                        onToggleTheme = { vm.toggleDark() },
                        dark = dark,
                    )
                    AppContextRow(
                        babyName = ui.baby?.nickname.orEmpty(),
                        dayLabel = dayLabel,
                        onCycleBaby = { vm.cycleBaby() },
                        onPrevDay = { vm.shiftDay(-1) },
                        onNextDay = { vm.shiftDay(1) },
                        canGoNext = ui.day.isBefore(today),
                        accent = accent,
                    )
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
        ) {
            composable(TopDest.Log.route) {
                LogRoute(
                    externalDay = ui.day,
                    onOpenTimer = { nav.navigate("timer") },
                    onOpenEdit = { id -> nav.navigate("edit/$id") },
                    onOpenNewEdit = { type -> nav.navigate("edit/new?type=$type") },
                    onOpenSearch = { nav.navigate("search") },
                )
            }
            composable(TopDest.Summary.route) { SummaryRoute() }
            composable(TopDest.Growth.route) { GrowthRoute() }
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
                    onOpenEdit = { id -> nav.navigate("edit/$id") },
                )
            }
            composable("export") {
                ExportRoute(onBack = { nav.popBackStack() })
            }
            composable("calendar") {
                CalendarRoute(onBack = { nav.popBackStack() })
            }
            composable("timer") {
                TimerRoute(onDone = { nav.popBackStack() })
            }
            composable(
                route = "edit/{recordId}?type={type}",
                arguments = listOf(
                    navArgument("recordId") { type = NavType.StringType },
                    navArgument("type") {
                        type = NavType.StringType
                        defaultValue = ""
                        nullable = true
                    },
                ),
            ) { entry ->
                val recordId = entry.arguments?.getString("recordId").orEmpty()
                val type = entry.arguments?.getString("type").orEmpty()
                RecordEditRoute(
                    recordId = recordId,
                    newTypeKey = type,
                    onDone = { nav.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun rememberDayLabel(day: LocalDate, today: LocalDate): String {
    return if (day == today) {
        "今天 · ${day.monthValue}月${day.dayOfMonth}日"
    } else {
        val wd = day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.CHINA)
        "${day.monthValue}月${day.dayOfMonth}日 ($wd)"
    }
}
