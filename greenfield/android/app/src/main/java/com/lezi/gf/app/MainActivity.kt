package com.lezi.gf.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.lezi.gf.app.ui.LeziNavShell
import com.lezi.gf.care.RecordType
import com.lezi.gf.settings.UiTemplate

class MainActivity : ComponentActivity() {
    private var pendingComposerType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingComposerType = extractComposerType(intent)
        val app = application as LeziGfApp
        setContent {
            val container = app.container
            var settings by remember { mutableStateOf(container.settings.get()) }
            var composerType by remember { mutableStateOf(pendingComposerType) }
            LeziTheme(dark = settings.darkTheme, template = settings.template) {
                Surface(Modifier.fillMaxSize()) {
                    LeziNavShell(
                        container = container,
                        settings = settings,
                        onSettingsChange = {
                            settings = it
                            container.settings.update { _ -> it }
                            container.persist()
                        },
                        pendingComposerTypeKey = composerType,
                        onPendingComposerConsumed = {
                            composerType = null
                            pendingComposerType = null
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingComposerType = extractComposerType(intent)
        // Recreate content binding via recreate for simple delivery
        recreate()
    }

    override fun onPause() {
        super.onPause()
        // persist() is a no-op when localDataGate is corrupt (never wipe damaged files)
        (application as LeziGfApp).container.persist()
    }

    private fun extractComposerType(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.action == ACTION_OPEN_COMPOSER) {
            return intent.getStringExtra(EXTRA_COMPOSER_TYPE)
                ?: RecordType.FORMULA.key
        }
        return intent.getStringExtra(EXTRA_COMPOSER_TYPE)
    }

    companion object {
        const val ACTION_OPEN_COMPOSER = "com.lezi.gf.app.OPEN_COMPOSER"
        const val EXTRA_COMPOSER_TYPE = "composer_type"
        const val EXTRA_BABY_UUID = "baby_uuid"
    }
}

private val WarmLight = lightColorScheme(
    primary = Color(0xFFE76F51),
    secondary = Color(0xFF2A9D8F),
    background = Color(0xFFFFF8F0),
    surface = Color(0xFFFFFBF5),
)

private val JournalLight = lightColorScheme(
    primary = Color(0xFF264653),
    secondary = Color(0xFF2A9D8F),
    background = Color(0xFFF7F7F5),
    surface = Color(0xFFFFFFFF),
)

private val WarmDark = darkColorScheme(
    primary = Color(0xFFE76F51),
    secondary = Color(0xFF2A9D8F),
)

private val JournalDark = darkColorScheme(
    primary = Color(0xFF8ECAE6),
    secondary = Color(0xFF2A9D8F),
)

@Composable
fun LeziTheme(dark: Boolean, template: UiTemplate, content: @Composable () -> Unit) {
    val scheme = when {
        dark && template == UiTemplate.JOURNAL -> JournalDark
        dark -> WarmDark
        template == UiTemplate.JOURNAL -> JournalLight
        else -> WarmLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
