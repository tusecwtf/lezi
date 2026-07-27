package com.lezi.babylog.feature.family

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.FamilyRole

/** Apply FLAG_SECURE for the lifetime of the current composition (invite QR). */
@Composable
internal fun SecureWindowWhileVisible() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Secondary member roster opened from the family-card count entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FamilyMembersListSheet(
    ui: FamilyUi,
    onRefreshMembers: () -> Unit,
    onEditMyDisplayName: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val visibleMembers = familyMembersForDisplay(
        ui.members,
        ui.displayName,
        ui.role,
        ui.membershipId,
        ui.membersLoaded,
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LeziSpacing.Page)
                .padding(bottom = LeziSpacing.Xxl),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("家人名单", style = LeziTypography.TitleSm)
                    Text(
                        familyMemberSummary(visibleMembers.size, ui.role, ui.membersLoaded),
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRefreshMembers, enabled = !ui.membersLoading) {
                    Text(if (ui.membersLoading) "刷新中…" else "刷新")
                }
            }
            visibleMembers.forEach { member ->
                FamilyMemberRow(
                    member = member,
                    onEditSelf = onEditMyDisplayName.takeIf { member.isSelf },
                )
            }
            ui.membersError?.let { error ->
                Text(
                    error,
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = LeziSpacing.Xs),
                )
            } ?: if (!ui.membersLoaded && !ui.membersLoading) {
                Text(
                    "连接家庭 Wi-Fi 后刷新完整列表",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = LeziSpacing.Xs),
                )
            } else Unit
        }
    }
}

@Composable
internal fun FamilyMemberRow(
    member: FamilyMember,
    onEditSelf: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = LeziSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = if (member.isSelf) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (member.isSelf) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        member.isSelf -> "我"
                        member.role == FamilyRole.Owner -> "管"
                        else -> "员"
                    },
                    style = LeziTypography.Label,
                )
            }
        }
        Spacer(Modifier.size(LeziSpacing.Sm))
        Column(Modifier.weight(1f)) {
            Text(
                familyMemberTitle(member),
                style = LeziTypography.BodyStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(familyRoleLabel(member.role))
                    if (member.isSelf) append(" · 本机")
                },
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (member.isSelf && onEditSelf != null) {
            androidx.compose.material3.TextButton(onClick = onEditSelf) {
                Text("改称呼")
            }
        }
    }
}

@Composable
internal fun FamilyGuideRow(
    step: String,
    title: String,
    detail: String,
    complete: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = CircleShape,
            color = if (complete) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (complete) "✓" else step,
                    style = LeziTypography.Label,
                    color = if (complete) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Spacer(Modifier.size(LeziSpacing.Sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = LeziTypography.BodyStrong)
            Text(
                detail,
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun FamilyScopeRow(
    marker: String,
    title: String,
    detail: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    marker,
                    style = LeziTypography.Eyebrow,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.size(LeziSpacing.Sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = LeziTypography.BodyStrong)
            Text(
                detail,
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
