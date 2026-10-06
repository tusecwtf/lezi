package com.lezi.babylog.feature.family.conflict

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.designsystem.LeziAlertDialog
import com.lezi.babylog.designsystem.LeziDetailTopBar
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziSurfacePanel
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.carelog.LOCAL_DISMISS_CONSEQUENCE
import com.lezi.babylog.domain.carelog.UnresolvedInboxIds
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LocalUnresolvedResolverHost @Inject constructor(
    private val careLog: CareLog,
) : ViewModel() {
    val inbox: StateFlow<com.lezi.babylog.domain.carelog.ConflictInbox> =
        careLog.observeOpenConflictInbox()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                com.lezi.babylog.domain.carelog.ConflictInbox(),
            )

    fun dismiss(inboxId: String, onDone: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            onDone(careLog.dismissUnresolvedLocally(inboxId))
        }
    }
}

@Composable
fun LocalUnresolvedResolverRoute(
    inboxId: String,
    onDismiss: () -> Unit,
    host: LocalUnresolvedResolverHost = hiltViewModel(),
) {
    val inbox by host.inbox.collectAsStateWithLifecycle()
    val item = inbox.items.firstOrNull { it.conflictId == inboxId }
    val ref = remember(inboxId) { UnresolvedInboxIds.parse(inboxId) }
    var confirming by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val title = item?.rootLabel ?: "未对齐项"
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("local_unresolved_resolver_sheet"),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                LeziDetailTopBar(
                    title = "处理$title",
                    onBack = onDismiss,
                )
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(LeziSpacing.Page),
                    verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
                ) {
                    Text(item?.title ?: inboxId, style = LeziTypography.BodyStrong)
                    item?.reasonLabel?.let { reason ->
                        Text(reason, style = LeziTypography.Meta)
                    }
                    notice?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                    LeziSurfacePanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("local_unresolved_keep"),
                        onClick = onDismiss,
                    ) {
                        Text("先留在本机", style = LeziTypography.TitleSm)
                        Text(
                            "先不处理。待处理计数不变。",
                            style = LeziTypography.Meta,
                        )
                    }
                    if (ref?.dismissible == true) {
                        LeziSurfacePanel(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("local_unresolved_drop"),
                            onClick = { confirming = true },
                        ) {
                            Text("从本机去掉", style = LeziTypography.TitleSm)
                            Text(LOCAL_DISMISS_CONSEQUENCE, style = LeziTypography.Meta)
                        }
                    } else {
                        Text(
                            "这项不能从本机去掉。",
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(LeziSpacing.Page),
                ) {
                    LeziPrimaryButton(
                        label = "先留在本机",
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().testTag("local_unresolved_keep_action"),
                    )
                }
            }
        }
    }
    if (confirming) {
        LeziAlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("从本机去掉") },
            text = { Text(LOCAL_DISMISS_CONSEQUENCE) },
            confirmButton = {
                LeziTextButton(
                    label = "只从这台手机去掉",
                    onClick = {
                        host.dismiss(inboxId) { result ->
                            confirming = false
                            result.onSuccess { onDismiss() }
                                .onFailure { error ->
                                    notice = error.message ?: "去掉失败"
                                }
                        }
                    },
                )
            },
            dismissButton = {
                LeziTextButton(label = "取消", onClick = { confirming = false })
            },
        )
    }
}
