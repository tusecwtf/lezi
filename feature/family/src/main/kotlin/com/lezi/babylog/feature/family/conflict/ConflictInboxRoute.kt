package com.lezi.babylog.feature.family.conflict

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziSurfacePanel
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.StateContainer
import com.lezi.babylog.designsystem.StateKind
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.carelog.ConflictInboxItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class ConflictInboxHost @Inject constructor(
    careLog: CareLog,
) : ViewModel() {
    private val phaseFlow = ConflictInboxPhaseFlow(
        upstream = careLog.observeOpenConflictInbox(),
        scope = viewModelScope,
    )

    /** 五相位对外状态：首帧是未加载，绝不会被渲染成「目前没有待处理项」。 */
    val phases: StateFlow<ConflictInboxPhase> = phaseFlow.phases

    /** 失败后的原地重试：重新订阅既有 observe 流，不新增轮询。 */
    fun retry() = phaseFlow.retry()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictInboxRoute(
    onDismiss: () -> Unit,
    onOpenConflict: (String) -> Unit,
    host: ConflictInboxHost = hiltViewModel(),
) {
    val phase by host.phases.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("conflict_inbox_sheet"),
    ) {
        ConflictInboxContent(
            phase = phase,
            onOpenConflict = onOpenConflict,
            onRetry = host::retry,
        )
    }
}

@Composable
fun ConflictInboxContent(
    phase: ConflictInboxPhase,
    onOpenConflict: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(LeziSpacing.Page),
        verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
    ) {
        Text("待处理", style = LeziThemeExt.typography.Title)
        // 顶部固定心智解释：什么时候东西会出现在这里（票 08 用户故事）。
        Text(
            CONFLICT_INBOX_EXPLANATION,
            style = LeziTypography.Meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (phase) {
            ConflictInboxPhase.NotLoaded, ConflictInboxPhase.Loading -> StateContainer(
                kind = StateKind.Loading,
                title = CONFLICT_INBOX_LOADING_TITLE,
                message = CONFLICT_INBOX_LOADING_MESSAGE,
            )
            ConflictInboxPhase.Error -> StateContainer(
                kind = StateKind.Error,
                title = CONFLICT_INBOX_ERROR_TITLE,
                message = CONFLICT_INBOX_ERROR_MESSAGE,
                actionLabel = CONFLICT_INBOX_RETRY_ACTION,
                onAction = onRetry,
            )
            ConflictInboxPhase.Empty -> StateContainer(
                kind = StateKind.Empty,
                title = CONFLICT_INBOX_EMPTY_TITLE,
                message = CONFLICT_INBOX_EMPTY_MESSAGE,
            )
            is ConflictInboxPhase.Content -> {
                Text("${phase.inbox.count} 项待处理", style = LeziTypography.Meta)
                phase.inbox.items.forEach { item ->
                    ConflictInboxCard(item, onOpenConflict)
                }
            }
        }
        Spacer(Modifier.height(LeziSpacing.Lg))
    }
}

@Composable
private fun ConflictInboxCard(
    item: ConflictInboxItem,
    onOpenConflict: (String) -> Unit,
) {
    val meta = conflictInboxCardMeta(item)
    LeziSurfacePanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = meta.onClickActionLabel,
                onClick = { onOpenConflict(item.conflictId) },
            )
            .semantics {
                contentDescription = meta.contentDescription(item.rootLabel, item.title)
            }
            .testTag("conflict_inbox_item_${item.conflictId}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${item.rootLabel} · ${item.title}", style = LeziTypography.BodyStrong)
                meta.metaLine?.let { line ->
                    Text(
                        line,
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                meta.actionLabel,
                style = LeziTypography.Label,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
