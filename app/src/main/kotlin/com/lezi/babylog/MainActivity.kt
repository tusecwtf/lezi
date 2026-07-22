package com.lezi.babylog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.domain.BootstrapInteractor
import com.lezi.babylog.feature.family.FamilyRoute
import com.lezi.babylog.feature.growth.GrowthRoute
import com.lezi.babylog.feature.log.LogRoute
import com.lezi.babylog.feature.onboarding.OnboardingRoute
import com.lezi.babylog.feature.settings.SettingsRoute
import com.lezi.babylog.feature.summary.SummaryRoute
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LeziTheme {
                LeziRoot()
            }
        }
    }
}

@HiltViewModel
class RootViewModel @Inject constructor(
    bootstrap: BootstrapInteractor,
) : ViewModel() {
    val hasBaby = bootstrap.observeHasBaby()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}

private enum class TopDest(val route: String, val label: String, val icon: ImageVector) {
    Log("log", "记录", Icons.Filled.Home),
    Summary("summary", "汇总", Icons.Filled.DateRange),
    Growth("growth", "成长", Icons.Filled.ShowChart),
    Family("family", "账户", Icons.Filled.Person),
    Settings("settings", "菜单", Icons.Filled.Settings),
}

@Composable
fun LeziRoot(vm: RootViewModel = hiltViewModel()) {
    val hasBaby by vm.hasBaby.collectAsStateWithLifecycle()
    if (!hasBaby) {
        OnboardingRoute(onFinished = { /* state flows from db */ })
        return
    }
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    Scaffold(
        modifier = Modifier.testTag(UiTags.ROOT),
        bottomBar = {
            NavigationBar {
                TopDest.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = current == dest.route,
                        onClick = {
                            nav.navigate(dest.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = TopDest.Log.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(TopDest.Log.route) { LogRoute() }
            composable(TopDest.Summary.route) { SummaryRoute() }
            composable(TopDest.Growth.route) { GrowthRoute() }
            composable(TopDest.Family.route) { FamilyRoute() }
            composable(TopDest.Settings.route) { SettingsRoute() }
        }
    }
}
