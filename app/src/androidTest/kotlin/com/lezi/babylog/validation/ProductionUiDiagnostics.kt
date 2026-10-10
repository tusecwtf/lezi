package com.lezi.babylog.validation

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import com.lezi.babylog.MainActivity
import com.lezi.babylog.RootViewModel
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Capture inside ActivityScenario.use, before scenario/rule teardown can replace the failed UI. */
internal class ProductionUiDiagnostics(
    private val fixture: ProductionAppFixture,
    private val compose: ComposeTestRule,
    private val caseName: String,
) {
    private var phase = "created"
    private val transitions = mutableListOf<String>()

    fun phase(description: String) {
        phase = description
        transitions += "${SystemClock.elapsedRealtime()} $description"
        fixture.instrumentation.sendStatus(0, Bundle().apply {
            putString("appGuardPhase", "$caseName: $description")
        })
    }

    fun <T> beforeTeardown(scenario: ActivityScenario<MainActivity>, block: () -> T): T = try {
        block()
    } catch (failure: Throwable) {
        runCatching { capture(scenario, "failure", failure) }
            .onFailure(failure::addSuppressed)
        throw failure
    }

    fun capture(scenario: ActivityScenario<MainActivity>, label: String, failure: Throwable? = null) {
        val directory = File(fixture.context.filesDir,
            "app-guard-diagnostics/$caseName-${SystemClock.elapsedRealtime()}-$label")
        runCatching { directory.mkdirs() }
        fun artifact(name: String, content: () -> String) {
            runCatching { File(directory, name).writeText(content()) }
                .onFailure { error -> runCatching { File(directory, "$name.error").writeText(error.stackTraceToString()) } }
        }
        runCatching {
            fixture.instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                try {
                    File(directory, "screen.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
            }
        }
        artifact("accessibility.txt") {
            buildString {
                var remaining = 3_000
                fun visit(node: AccessibilityNodeInfo?, depth: Int) {
                    if (node == null || remaining-- <= 0 || depth > 80) return
                    val bounds = Rect().also(node::getBoundsInScreen)
                    appendLine("${"  ".repeat(depth)}package=${node.packageName} class=${node.className} " +
                        "text=${node.text} description=${node.contentDescription} id=${node.viewIdResourceName} " +
                        "click=${node.isClickable} editable=${node.isEditable} focused=${node.isFocused} bounds=$bounds")
                    for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
                    node.recycle()
                }
                val automation = fixture.instrumentation.uiAutomation
                automation.windows.forEach { window ->
                    appendLine("window=${window.id} type=${window.type} active=${window.isActive}")
                    visit(window.root, 0)
                }
                appendLine("activeRoot:")
                visit(automation.rootInActiveWindow, 0)
            }
        }
        artifact("state.txt") {
            buildString {
                appendLine("case=$caseName phase=$phase elapsed=${SystemClock.elapsedRealtime()}")
                appendLine("scenario=${scenario.state} defaultLocale=${Locale.getDefault()}")
                appendLine(transitions.joinToString("\n"))
                appendLine(failure?.stackTraceToString().orEmpty())
                appendLine("startup=${AppStartupObservation.snapshot()}")
                runCatching {
                    scenario.onActivity { activity ->
                        appendLine("activity=${activity.javaClass.name} lifecycle=${activity.lifecycle.currentState} " +
                            "finishing=${activity.isFinishing} destroyed=${activity.isDestroyed} " +
                            "locale=${activity.resources.configuration.locales[0]}")
                        // These scenarios have already reached the production root before actions.
                        val root = ViewModelProvider(activity)[RootViewModel::class.java]
                        appendLine("rootUi=${root.ui.value}")
                        appendLine("force=${root.forcedAppUpdate.value} recovery=${root.forcedUpdateSessionRecovery.value} " +
                            "expanded=${root.forceShellSessionRecoveryExpanded.value}")
                    }
                }.onFailure { appendLine("activityUnavailable=$it") }
                runCatching {
                    runBlocking {
                        withTimeout(3_000) {
                            appendLine("expectedBabyId=${fixture.babyId} currentBaby=${fixture.careLog.getCurrentBaby()}")
                            appendLine("babies=${fixture.careLog.listBabies()}")
                            // Credential-free presentation only; never serialize SyncSession tokens.
                            appendLine("sessionPresentation=${fixture.sync.sessionPresentation().first()}")
                        }
                    }
                }.onFailure { appendLine("domainUnavailable=$it") }
            }
        }
        for (unmerged in listOf(false, true)) {
            artifact("semantics-${if (unmerged) "unmerged" else "merged"}.txt") {
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = unmerged).fetchSemanticsNodes()
                buildString {
                    var remaining = 3_000
                    fun visit(node: SemanticsNode, depth: Int) {
                        if (remaining-- <= 0 || depth > 80) return
                        appendLine("${"  ".repeat(depth)}id=${node.id} bounds=${node.boundsInRoot} ${node.config}")
                        node.children.forEach { visit(it, depth + 1) }
                    }
                    roots.forEach { visit(it, 0) }
                }
            }
        }
        fixture.instrumentation.sendStatus(0, Bundle().apply {
            putString("appGuardDiagnostics", "files/${directory.relativeTo(fixture.context.filesDir).path}; phase=$phase")
        })
    }
}
