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
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.carelog.ConflictInbox
import com.lezi.babylog.domain.carelog.ConflictInboxActor
import com.lezi.babylog.domain.carelog.ConflictInboxBranchTombstone
import com.lezi.babylog.domain.carelog.ConflictInboxItem
import com.lezi.babylog.domain.carelog.ConflictInboxMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class ConflictInboxHost @Inject constructor(
    careLog: CareLog,
) : ViewModel() {
    val inbox: StateFlow<ConflictInbox> = careLog.observeOpenConflictInbox()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConflictInbox())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictInboxRoute(
    onDismiss: () -> Unit,
    onOpenConflict: (String) -> Unit,
    host: ConflictInboxHost = hiltViewModel(),
) {
    val inbox by host.inbox.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("conflict_inbox_sheet"),
    ) {
        ConflictInboxContent(inbox, onOpenConflict)
    }
}

@Composable
fun ConflictInboxContent(
    inbox: ConflictInbox,
    onOpenConflict: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(LeziSpacing.Page),
        verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
    ) {
        Text("同步冲突", style = LeziTypography.Title)
        Text("${inbox.count} 项待处理", style = LeziTypography.Meta)
        if (inbox.items.isEmpty()) {
            Text("目前没有待处理冲突", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        inbox.items.forEach { item ->
            ConflictInboxCard(item, onOpenConflict)
        }
        Spacer(Modifier.height(LeziSpacing.Lg))
    }
}

@Composable
private fun ConflictInboxCard(
    item: ConflictInboxItem,
    onOpenConflict: (String) -> Unit,
) {
    val branchTombstone = item.branchTombstone
    val deletion = when {
        item.stableTombstone == true -> "当前已删除"
        branchTombstone is ConflictInboxBranchTombstone.Known &&
            branchTombstone.hasCandidate -> "含删除候选"
        item.stableTombstone == false &&
            branchTombstone is ConflictInboxBranchTombstone.Known -> "当前保留"
        else -> "删除状态待加载"
    }
    val actor = when (val value = item.actor) {
        is ConflictInboxActor.Known -> "提交者 ${value.label}"
        ConflictInboxActor.RequiresDetail -> "提交者详情待加载"
    }
    val media = when (val value = item.media) {
        is ConflictInboxMedia.Known -> "${value.totalCount}张照片"
        is ConflictInboxMedia.RequiresDetail -> if (value.knownLocalCount > 0) {
            "至少${value.knownLocalCount}张照片，详情待加载"
        } else {
            "照片详情待加载"
        }
    }
    val baby = item.babyLabel?.let { "宝宝 $it" }
    LeziSurfacePanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = "审阅${item.rootLabel}冲突",
                onClick = { onOpenConflict(item.conflictId) },
            )
            .semantics {
                contentDescription = listOfNotNull(
                    item.rootLabel,
                    item.title,
                    baby,
                    actor,
                    deletion,
                    media,
                ).joinToString("，")
            }
            .testTag("conflict_inbox_item_${item.conflictId}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${item.rootLabel} · ${item.title}", style = LeziTypography.BodyStrong)
                Text(
                    listOfNotNull(baby, actor, deletion, media)
                        .joinToString(" · "),
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("审阅", style = LeziTypography.Label, color = MaterialTheme.colorScheme.primary)
        }
    }
}
