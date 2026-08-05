package com.lezi.gf.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.lezi.gf.app.AppContainer
import com.lezi.gf.app.ui.screens.AccountScreen
import com.lezi.gf.app.ui.screens.GrowthScreen
import com.lezi.gf.app.ui.screens.LogScreen
import com.lezi.gf.app.ui.screens.MenuScreen
import com.lezi.gf.app.ui.screens.OnboardingScreen
import com.lezi.gf.app.ui.screens.SummaryScreen
import com.lezi.gf.app.ui.screens.UpdateShells
import com.lezi.gf.app.ui.theme.LeziMotion
import com.lezi.gf.kernel.GfResult
import com.lezi.gf.settings.LocalSettings

private data class Tab(val label: String, val icon: ImageVector, val longPressSwitchesBaby: Boolean)

/**
 * Five-tab shell: 记录 / 汇总 / 成长 / 账户 / 菜单.
 * Long-press 记录/汇总/成长 opens baby switch (ui.md §4).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LeziNavShell(
    container: AppContainer,
    settings: LocalSettings,
    onSettingsChange: (LocalSettings) -> Unit,
    pendingComposerTypeKey: String? = null,
    onPendingComposerConsumed: () -> Unit = {},
) {
    val gateMsg = container.localDataGate.gateMessage()
    if (gateMsg != null) {
        DataGateScreen(message = gateMsg)
        return
    }

    val forced = container.sync.forcedUpdateShell()
    if (forced != null) {
        UpdateShells.Forced(update = forced)
        return
    }

    var babyReady by remember {
        mutableStateOf(container.family.currentBaby() != null)
    }
    if (!babyReady) {
        OnboardingScreen(container = container) {
            babyReady = true
            container.persist()
        }
        return
    }

    val tabs = listOf(
        Tab("记录", Icons.Default.EditNote, longPressSwitchesBaby = true),
        Tab("汇总", Icons.Default.BarChart, longPressSwitchesBaby = true),
        Tab("成长", Icons.Default.ChildCare, longPressSwitchesBaby = true),
        Tab("账户", Icons.Default.AccountCircle, longPressSwitchesBaby = false),
        Tab("菜单", Icons.Default.Menu, longPressSwitchesBaby = false),
    )
    var selected by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var showBabySwitcher by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = {
                            val interaction = remember { MutableInteractionSource() }
                            Icon(
                                tab.icon,
                                contentDescription = if (tab.longPressSwitchesBaby) {
                                    "${tab.label}，长按切换宝宝"
                                } else {
                                    tab.label
                                },
                                modifier = Modifier
                                    .semantics {
                                        contentDescription = if (tab.longPressSwitchesBaby) {
                                            "${tab.label}，长按切换宝宝"
                                        } else {
                                            tab.label
                                        }
                                    }
                                    .then(
                                        if (tab.longPressSwitchesBaby) {
                                            Modifier.combinedClickable(
                                                interactionSource = interaction,
                                                indication = null,
                                                onClick = { selected = index },
                                                onLongClick = { showBabySwitcher = true },
                                            )
                                        } else {
                                            Modifier
                                        },
                                    ),
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        @Suppress("UNUSED_EXPRESSION")
        refresh
        val mod = Modifier.padding(padding)
        val tabAnimMs = LeziMotion.millis(settings.reduceMotion, LeziMotion.Base)
        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                fadeIn(animationSpec = tween(tabAnimMs)) togetherWith
                    fadeOut(animationSpec = tween(tabAnimMs))
            },
            label = "lezi-tab-crossfade",
            modifier = mod.fillMaxSize(),
        ) { tab ->
            when (tab) {
                0 -> LogScreen(
                    container = container,
                    settings = settings,
                    modifier = Modifier.fillMaxSize(),
                    pendingComposerTypeKey = pendingComposerTypeKey,
                    onPendingComposerConsumed = onPendingComposerConsumed,
                    onChanged = {
                        refresh++
                        container.persist()
                    },
                )
                1 -> SummaryScreen(container, settings, Modifier.fillMaxSize()) {
                    refresh++
                    container.persist()
                }
                2 -> GrowthScreen(container, settings, Modifier.fillMaxSize()) {
                    refresh++
                    container.persist()
                }
                3 -> AccountScreen(container, settings, Modifier.fillMaxSize()) {
                    refresh++
                    container.persist()
                }
                4 -> MenuScreen(container, settings, onSettingsChange, Modifier.fillMaxSize()) {
                    refresh++
                    container.persist()
                }
            }
        }
        container.sync.optionalUpdateShell()?.let { opt ->
            UpdateShells.Optional(
                update = opt,
                onLater = { container.sync.dismissOptionalUpdate() },
                onNow = { },
            )
        }
    }

    if (showBabySwitcher) {
        val babies = container.family.allBabies()
        val current = container.family.currentBaby()?.clientUuid
        AlertDialog(
            onDismissRequest = { showBabySwitcher = false },
            title = { Text("切换宝宝") },
            text = {
                androidx.compose.foundation.layout.Column {
                    if (babies.isEmpty()) {
                        Text("暂无宝宝")
                    } else {
                        babies.forEach { b ->
                            TextButton(
                                onClick = {
                                    when (container.family.switchBaby(b.clientUuid)) {
                                        is GfResult.Ok -> {
                                            showBabySwitcher = false
                                            refresh++
                                            container.persist()
                                        }
                                        is GfResult.Err -> Unit
                                    }
                                },
                            ) {
                                Text(
                                    buildString {
                                        append(b.nickname)
                                        if (b.clientUuid == current) append("（当前）")
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBabySwitcher = false }) { Text("关闭") }
            },
        )
    }
}

@Composable
fun DataGateScreen(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(message)
    }
}
