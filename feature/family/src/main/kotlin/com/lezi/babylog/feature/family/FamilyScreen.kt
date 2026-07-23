package com.lezi.babylog.feature.family

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.LocalUserDao
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.PageScaffoldBackground
import com.lezi.babylog.designsystem.SectionHeading
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.babyAgeLabel
import com.lezi.babylog.sync.SyncNotEnabledException
import com.lezi.babylog.sync.SyncPort
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FamilyUi(
    val deviceId: String = "",
    val displayName: String = "我（本机）",
    val status: SyncStatus = SyncStatus.Disabled,
    val enabled: Boolean = false,
    val hasLocalBaby: Boolean = false,
    val familyId: String = "1",
    val current: Baby? = null,
    val babies: List<Baby> = emptyList(),
)

@HiltViewModel
class FamilyViewModel @Inject constructor(
    private val sync: SyncPort,
    private val localUserDao: LocalUserDao,
    private val familyDao: FamilyDao,
    private val careLog: CareLog,
) : ViewModel() {
    val ui = combine(
        sync.status(),
        careLog.observeHasBaby(),
        careLog.observeCurrentBaby(),
        careLog.observeBabies(),
    ) { st, hasBaby, current, babies ->
        val user = localUserDao.get()
        val fam = familyDao.listAll().firstOrNull()
        FamilyUi(
            deviceId = user?.deviceId ?: "—",
            displayName = user?.displayName ?: "我（本机）",
            status = st,
            enabled = sync.isEnabled(),
            hasLocalBaby = hasBaby,
            familyId = fam?.id?.toString() ?: "1",
            current = current,
            babies = babies,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FamilyUi())

    fun setCurrent(id: Long) {
        viewModelScope.launch { careLog.setCurrentBaby(id) }
    }

    fun createInvite(onMessage: (String) -> Unit) {
        viewModelScope.launch {
            val familyId = ui.value.familyId
            val result = sync.createInvite(familyId)
            onMessage(
                result.fold(
                    onSuccess = { "共享码 ${it.code}（24 小时内有效）" },
                    onFailure = {
                        if (it is SyncNotEnabledException) "家庭同步将在后续版本开放" else (it.message ?: "失败")
                    },
                ),
            )
        }
    }

    fun join(code: String, clearFirst: Boolean, onMessage: (String) -> Unit) {
        viewModelScope.launch {
            if (ui.value.hasLocalBaby && !clearFirst) {
                onMessage("本机已有宝宝数据。请先 TXT 导出，或选择清空本机后加入。")
                return@launch
            }
            if (clearFirst) careLog.clearAllLocalData()
            val result = sync.joinWithCode(code.trim())
            onMessage(
                result.fold(
                    onSuccess = {
                        sync.pull(it.id.toString())
                        "已加入家庭 ${it.id}"
                    },
                    onFailure = {
                        if (it is SyncNotEnabledException) "家庭同步将在后续版本开放" else (it.message ?: "加入失败")
                    },
                ),
            )
        }
    }

    fun leave(onMessage: (String) -> Unit) {
        viewModelScope.launch {
            val id = ui.value.familyId
            val result = sync.leave(id)
            onMessage(result.fold({ "已离开家庭" }, { it.message ?: "失败" }))
        }
    }

    fun pullNow(onMessage: (String) -> Unit) {
        viewModelScope.launch {
            val id = ui.value.familyId
            sync.push(id)
            val r = sync.pull(id)
            onMessage(r.fold({ "已同步" }, { it.message ?: "同步失败" }))
        }
    }
}

@Composable
fun FamilyRoute(vm: FamilyViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    var showJoin by remember { mutableStateOf(false) }
    var joinCode by remember { mutableStateOf("") }
    var confirmClearJoin by remember { mutableStateOf(false) }
    val current = ui.current

    PageScaffoldBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(LeziSpacing.Page),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
        ) {
            com.lezi.babylog.designsystem.PageHero(
                eyebrow = "一起照顾，一起记",
                title = "账户",
                subtitle = "宝宝档案、家庭成员和同步设置都在这里。",
            )

            // Current baby card (prototype account hero)
            LeziCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val accent = current?.themeColorArgb?.let { Color(it) }
                            ?: MaterialTheme.colorScheme.primary
                        Box(
                            Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(accent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                current?.nickname?.take(1) ?: "乐",
                                color = Color.White,
                                style = LeziTypography.Title,
                            )
                        }
                        Spacer(Modifier.size(LeziSpacing.Sm))
                        Column {
                            Text("当前宝宝", style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(current?.nickname ?: "—", style = LeziTypography.TitleSm)
                            val age = current?.let { babyAgeLabel(it.birthdayEpochDay) }.orEmpty()
                            val sex = when (current?.sex?.name) {
                                "MALE" -> "男宝"
                                "FEMALE" -> "女宝"
                                else -> ""
                            }
                            val birth = current?.let {
                                LocalDate.ofEpochDay(it.birthdayEpochDay).toString()
                            }.orEmpty()
                            Text(
                                listOfNotNull(
                                    birth.takeIf { it.isNotBlank() }?.let { "${it}出生" },
                                    sex.takeIf { it.isNotBlank() },
                                    age.takeIf { it.isNotBlank() },
                                ).joinToString(" · "),
                                style = LeziTypography.Meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (ui.babies.size > 1) {
                        LeziSecondaryButton("切换", onClick = {
                            val cur = ui.current?.id
                            val idx = ui.babies.indexOfFirst { it.id == cur }.takeIf { it >= 0 } ?: 0
                            val next = ui.babies[(idx + 1) % ui.babies.size]
                            vm.setCurrent(next.id)
                        })
                    }
                }
            }

            LeziCard(modifier = Modifier.fillMaxWidth()) {
                Text("数据仅保存在本机 · 无需登录", style = LeziTypography.BodyStrong)
                Text(
                    "本机 ID：${ui.deviceId.take(12).uppercase()}",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeading(title = "宝宝档案")
            ui.babies.forEach { b ->
                val selected = b.id == current?.id
                LeziCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { vm.setCurrent(b.id) },
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(b.themeColorArgb)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(b.nickname.take(1), color = Color.White, style = LeziTypography.TitleSm)
                            }
                            Spacer(Modifier.size(LeziSpacing.Sm))
                            Column {
                                Text(b.nickname, style = LeziTypography.BodyStrong)
                                Text(
                                    babyAgeLabel(b.birthdayEpochDay),
                                    style = LeziTypography.Meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            if (selected) "当前" else "切换",
                            style = LeziTypography.Label,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                "宝宝档案不可删除；可在菜单添加宝宝。",
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeading(title = "家人一起记")
            LeziPrimaryButton(
                "新建家庭 / 生成共享码",
                onClick = { vm.createInvite { message = it } },
                modifier = Modifier.fillMaxWidth(),
            )
            LeziSecondaryButton(
                "输入邀请码",
                onClick = { showJoin = true },
                modifier = Modifier.fillMaxWidth(),
            )
            LeziSecondaryButton(
                "扫码加入",
                onClick = { message = if (ui.enabled) "请使用「输入邀请码」" else "家庭同步将在后续版本开放" },
                modifier = Modifier.fillMaxWidth(),
            )
            if (ui.enabled) {
                LeziSecondaryButton(
                    "立即同步",
                    onClick = { vm.pullNow { message = it } },
                    modifier = Modifier.fillMaxWidth(),
                )
                LeziSecondaryButton(
                    "离开家庭",
                    onClick = { vm.leave { message = it } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                if (ui.enabled) "加入前将全量共享家庭记录。设置与深色模式不同步。"
                else "家庭同步将在后续版本开放",
                style = LeziTypography.Body,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(LeziSpacing.Xxl))
        }
    }

    if (showJoin) {
        AlertDialog(
            onDismissRequest = { showJoin = false },
            title = { Text("加入家庭") },
            text = {
                Column {
                    Text("加入后将全量共享该家庭数据。若本机已有宝宝，默认拒绝；可先导出 TXT。")
                    Spacer(Modifier.height(LeziSpacing.Sm))
                    OutlinedTextField(
                        value = joinCode,
                        onValueChange = { joinCode = it },
                        label = { Text("邀请码") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showJoin = false
                        if (ui.hasLocalBaby) confirmClearJoin = true
                        else vm.join(joinCode, clearFirst = false) { message = it }
                    },
                ) { Text("加入") }
            },
            dismissButton = {
                TextButton(onClick = { showJoin = false }) { Text("取消") }
            },
        )
    }

    if (confirmClearJoin) {
        AlertDialog(
            onDismissRequest = { confirmClearJoin = false },
            title = { Text("本机已有数据") },
            text = { Text("默认不能直接加入。可先 TXT 导出，或清空本机后再加入。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearJoin = false
                        vm.join(joinCode, clearFirst = true) { message = it }
                    },
                ) { Text("清空本机后加入") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearJoin = false }) { Text("取消") }
            },
        )
    }

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text("知道了") }
            },
        )
    }
}
