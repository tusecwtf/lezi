package com.lezi.gf.app.ui.account

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lezi.gf.app.AppContainer
import com.lezi.gf.app.invite.InviteScanHandler
import com.lezi.gf.app.ui.screens.runForegroundSync
import com.lezi.gf.app.ui.theme.LeziDensity
import com.lezi.gf.family.JoinState
import com.lezi.gf.kernel.GfResult
import com.lezi.gf.syncsession.toChinese

enum class AccountRoute {
    Overview,
    Network,
    Members,
    ConnectWizard,
    Babies,
    ScanJoin,
}

/**
 * Navigable account stack: overview / network / members+QR / connect wizard / babies.
 */
@Composable
fun AccountStack(
    container: AppContainer,
    density: LeziDensity,
    modifier: Modifier = Modifier,
    onChanged: () -> Unit,
) {
    var route by remember { mutableStateOf(AccountRoute.Overview) }
    when (route) {
        AccountRoute.Overview -> AccountOverview(
            container = container,
            density = density,
            modifier = modifier,
            onNavigate = { route = it },
            onChanged = onChanged,
        )
        AccountRoute.Network -> FamilyNetworkScreen(
            container = container,
            modifier = modifier,
            onBack = { route = AccountRoute.Overview },
            onChanged = onChanged,
        )
        AccountRoute.Members -> MembersQrScreen(
            container = container,
            modifier = modifier,
            onBack = { route = AccountRoute.Overview },
            onChanged = onChanged,
        )
        AccountRoute.ConnectWizard -> ConnectFamilyWizard(
            container = container,
            modifier = modifier,
            onBack = { route = AccountRoute.Overview },
            onChanged = onChanged,
            onOpenScan = { route = AccountRoute.ScanJoin },
        )
        AccountRoute.Babies -> BabyManagementScreen(
            container = container,
            modifier = modifier,
            onBack = { route = AccountRoute.Overview },
            onChanged = onChanged,
        )
        AccountRoute.ScanJoin -> ScanJoinScreen(
            container = container,
            modifier = modifier,
            onBack = { route = AccountRoute.Overview },
            onChanged = onChanged,
        )
    }
}

@Composable
private fun AccountOverview(
    container: AppContainer,
    density: LeziDensity,
    modifier: Modifier,
    onNavigate: (AccountRoute) -> Unit,
    onChanged: () -> Unit,
) {
    val acc = container.family.account()
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(density.panelContent)
            .semantics { contentDescription = "账户总览" },
    ) {
        Text("账户", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        when (acc.joinState) {
            JoinState.UNJOINED -> Text("未加入家庭 · 本机离线可用")
            JoinState.PENDING_APPROVAL -> Text("等待管理员批准…")
            JoinState.JOINED -> {
                Text("家庭：${acc.familyName}")
                Text("称呼：${acc.displayName} · ${if (acc.role == "owner") "管理员" else "成员"}")
                val (st, n) = container.sync.shallowStatus()
                Text(st.toChinese(n))
            }
            JoinState.BLOCKED_TRUST -> Text("信任阻断：${acc.blockReason}")
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onNavigate(AccountRoute.ConnectWizard) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (acc.joinState == JoinState.JOINED) "连接向导 / 重连" else "连接家庭向导") }
        if (acc.joinState != JoinState.JOINED) {
            Button(
                onClick = { onNavigate(AccountRoute.ScanJoin) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("扫描成员授权码") }
        }
        Button(
            onClick = { onNavigate(AccountRoute.Network) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("家庭网络设置") }
        Button(
            onClick = { onNavigate(AccountRoute.Members) },
            modifier = Modifier.fillMaxWidth(),
            enabled = acc.joinState == JoinState.JOINED,
        ) { Text("成员与登录 QR") }
        Button(
            onClick = { onNavigate(AccountRoute.Babies) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("宝宝管理") }
        if (acc.joinState == JoinState.JOINED) {
            TextButton(onClick = {
                runForegroundSync(container)
                onChanged()
            }) { Text("立即同步") }
            TextButton(onClick = {
                container.sync.exitDevice()
                container.family.clearFamilyLocalData("exit_device")
                onChanged()
            }) { Text("退出本设备") }
        }
        if (acc.joinState == JoinState.BLOCKED_TRUST) {
            Button(onClick = {
                container.family.clearFamilyLocalData("forget_and_reconnect")
                onChanged()
            }) { Text("忘记并重新连接") }
        }
    }
}

@Composable
private fun FamilyNetworkScreen(
    container: AppContainer,
    modifier: Modifier,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    val acc = container.family.account()
    var endpoint by remember { mutableStateOf(acc.endpoint) }
    var message by remember { mutableStateOf("") }
    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp)
            .semantics { contentDescription = "家庭网络设置" },
    ) {
        Row {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Text("家庭网络", style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            label = { Text("HTTPS 地址") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            container.family.setEndpoint(endpoint)
            container.sync.configureEndpoint(endpoint)
            message = when (val cap = container.sync.capability()) {
                is GfResult.Ok -> "能力协商成功"
                is GfResult.Err -> "协商失败: $cap"
            }
            onChanged()
        }) { Text("保存并探活") }
        if (message.isNotBlank()) Text(message)
        Text("浅同步状态不在独立同步中心；下拉/打开/前台更新。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MembersQrScreen(
    container: AppContainer,
    modifier: Modifier,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    val acc = container.family.account()
    var displayPayload by remember { mutableStateOf<String?>(null) }
    var bareCode by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .semantics { contentDescription = "成员与登录QR" },
    ) {
        Row {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Text("成员与设备", style = MaterialTheme.typography.titleLarge)
        }
        Text("家庭：${acc.familyName}")
        Text("成员名册")
        container.family.membershipNames().forEach { (id, name) ->
            Text("· $name ($id)")
        }
        if (container.family.isOwner()) {
            Button(onClick = {
                when (val r = container.sync.createMemberQr()) {
                    is GfResult.Ok -> {
                        bareCode = r.value.code
                        val ep = acc.endpoint.ifBlank { "https://127.0.0.1:18765" }
                        displayPayload = InviteScanHandler.adminDisplayPayload(
                            apiEndpoint = ep,
                            code = r.value.code,
                            trustedSpki = container.sync.trustedSpkiSha256
                                ?: acc.trustedSpkiSha256,
                        )
                        message = "已生成系统相机可识别的邀请 URL（#v1 授权片段）。展示期应 FLAG_SECURE。根密码不进入 QR。"
                    }
                    is GfResult.Err -> message = "生成失败"
                }
                onChanged()
            }) { Text("生成成员登录 QR") }
            displayPayload?.let { payload ->
                Text("系统相机 / 扫码载荷（完整 URL）", style = MaterialTheme.typography.labelMedium)
                Text(payload, style = MaterialTheme.typography.bodySmall)
                bareCode?.let { Text("原始 code（仅本机调试）：$it", style = MaterialTheme.typography.labelSmall) }
                Text(
                    "未安装设备：系统相机打开 /join 邀请安装页（不完成登录）。装后请回 App 再扫同一码。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            Text("普通成员请使用「扫描成员授权码」或连接向导中的扫描入口。")
        }
        if (message.isNotBlank()) Text(message)
    }
}

/**
 * Scan / paste entry: system-camera QR strings, invite URLs, or bare grant codes.
 * Camera permission only requested when user taps scan intent; paste always works.
 */
@Composable
fun ScanJoinScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    var paste by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var lastOutcome by remember { mutableStateOf<String?>(null) }

    fun openBrowser(url: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            message = "无法打开浏览器：$url"
        }
    }

    fun process(raw: String) {
        when (
            val o = InviteScanHandler.handle(
                container = container,
                raw = raw,
                openInviteInBrowser = { openBrowser(it) },
            )
        ) {
            is InviteScanHandler.Outcome.OpenInviteInstall -> {
                lastOutcome = "invite_install"
                message = o.userMessage
                onChanged()
            }
            is InviteScanHandler.Outcome.Joined -> {
                lastOutcome = "joined"
                message = o.userMessage
                onChanged()
            }
            is InviteScanHandler.Outcome.Failed -> {
                lastOutcome = "failed"
                message = o.userMessage
            }
        }
    }

    // Optional: external barcode scanner apps (ZXing-compatible) return SCAN_RESULT
    val zxingLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        val scanned = data?.getStringExtra("SCAN_RESULT")
            ?: data?.getStringExtra("scan_result")
        if (!scanned.isNullOrBlank()) {
            paste = scanned
            process(scanned)
        } else if (result.resultCode != android.app.Activity.RESULT_CANCELED) {
            message = "未读到扫码结果，请粘贴或重试"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            // Permission alone does not decode QR; try ZXing intent or fall back to paste
            try {
                val intent = Intent("com.google.zxing.client.android.SCAN").apply {
                    putExtra("SCAN_MODE", "QR_CODE_MODE")
                }
                zxingLauncher.launch(intent)
            } catch (_: Exception) {
                message = "未找到系统/已安装扫码器。请用系统相机扫管理员码后粘贴 URL，或粘贴授权码。"
            }
        } else {
            message = "已拒绝相机权限；仍可手动粘贴扫描内容。"
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .semantics { contentDescription = "扫描成员授权码" },
    ) {
        Row {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Text("扫描授权码", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "支持：系统相机打开的邀请 URL（/join#v1…）、授权 JSON、或管理员原始 code。邀请页不会完成登录。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = paste,
            onValueChange = { paste = it },
            label = { Text("粘贴扫描结果") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        Button(
            onClick = { process(paste) },
            enabled = paste.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("处理扫描内容") }
        Button(
            onClick = {
                val hasCam = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                if (hasCam) {
                    try {
                        zxingLauncher.launch(
                            Intent("com.google.zxing.client.android.SCAN").apply {
                                putExtra("SCAN_MODE", "QR_CODE_MODE")
                            },
                        )
                    } catch (_: Exception) {
                        message = "未找到扫码器应用。请用手机系统相机扫描后，将链接粘贴到上方。"
                    }
                } else {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("请求相机 / 打开扫码器") }
        Text(
            "无扫码器时：用系统相机扫管理员展示的 URL → 浏览器安装页 → 装好乐记后回此处粘贴同一 URL 或 code。",
            style = MaterialTheme.typography.labelSmall,
        )
        if (message.isNotBlank()) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
        lastOutcome?.let {
            Text("结果标记：$it", style = MaterialTheme.typography.labelSmall)
        }
        Text(
            "当前状态：${container.family.account().joinState}",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ConnectFamilyWizard(
    container: AppContainer,
    modifier: Modifier,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onOpenScan: () -> Unit = {},
) {
    var step by remember { mutableStateOf(0) }
    var endpoint by remember { mutableStateOf(container.family.account().endpoint.ifBlank { "https://127.0.0.1:18765" }) }
    var familyName by remember { mutableStateOf("我家") }
    var displayName by remember { mutableStateOf("家长") }
    var secret by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .semantics { contentDescription = "连接家庭向导" },
    ) {
        Row {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Text("连接家庭", style = MaterialTheme.typography.titleLarge)
        }
        TextButton(onClick = onOpenScan) { Text("我有成员授权码 / 去扫描") }
        Text("步骤 ${step + 1}/3", style = MaterialTheme.typography.labelMedium)
        when (step) {
            0 -> {
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("HTTPS 地址") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = {
                    container.family.setEndpoint(endpoint)
                    container.sync.configureEndpoint(endpoint)
                    message = when (container.sync.capability()) {
                        is GfResult.Ok -> {
                            step = 1
                            "探活成功"
                        }
                        is GfResult.Err -> "探活失败"
                    }
                }) { Text("下一步：探活") }
            }
            1 -> {
                OutlinedTextField(value = familyName, onValueChange = { familyName = it }, label = { Text("家庭名") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = displayName, onValueChange = { displayName = it }, label = { Text("我的称呼") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = secret, onValueChange = { secret = it }, label = { Text("根密码（建家/登录）") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { step = 2 }) { Text("下一步：选择动作") }
            }
            2 -> {
                Button(onClick = {
                    when (val st = container.sync.setupStatus()) {
                        is GfResult.Ok -> {
                            if (st.value.state == "empty") {
                                when (
                                    val s = container.sync.createFamily(
                                        secret, familyName, displayName, "本机设备",
                                    )
                                ) {
                                    is GfResult.Ok -> {
                                        container.family.markJoined(
                                            s.value.family_id,
                                            s.value.family_name,
                                            s.value.membership_id,
                                            s.value.role,
                                            s.value.display_name,
                                            s.value.device_id,
                                            s.value.access_token,
                                            s.value.refresh_token,
                                            container.sync.trustedSpkiSha256,
                                            endpoint,
                                        )
                                        secret = ""
                                        message = "建家成功"
                                        onChanged()
                                    }
                                    is GfResult.Err -> message = "建家失败"
                                }
                            } else {
                                message = "服务器非空，请登录或加入"
                            }
                        }
                        is GfResult.Err -> message = "状态查询失败"
                    }
                }) { Text("信任并建家") }
                Button(onClick = {
                    when (val j = container.sync.applyJoin(displayName, "成员设备")) {
                        is GfResult.Ok -> {
                            container.family.markPendingApproval(displayName)
                            message = "已申请: ${j.value.request_id}"
                            onChanged()
                        }
                        is GfResult.Err -> message = "申请失败"
                    }
                }) { Text("申请加入") }
                Button(onClick = {
                    when (val s = container.sync.ownerLogin(secret, "本机设备")) {
                        is GfResult.Ok -> {
                            container.family.markJoined(
                                s.value.family_id,
                                s.value.family_name,
                                s.value.membership_id,
                                s.value.role,
                                s.value.display_name,
                                s.value.device_id,
                                s.value.access_token,
                                s.value.refresh_token,
                                container.sync.trustedSpkiSha256,
                                endpoint,
                            )
                            secret = ""
                            message = "管理员登录成功"
                            onChanged()
                        }
                        is GfResult.Err -> message = "登录失败"
                    }
                }) { Text("管理员登录") }
            }
        }
        if (message.isNotBlank()) Text(message)
    }
}

@Composable
private fun BabyManagementScreen(
    container: AppContainer,
    modifier: Modifier,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp)
            .semantics { contentDescription = "宝宝管理" },
    ) {
        Row {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Text("宝宝管理", style = MaterialTheme.typography.titleLarge)
        }
        container.family.allBabies().forEach { b ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    buildString {
                        append(b.nickname)
                        if (b.familyAuthority) append("（家庭）")
                        if (b.clientUuid == container.family.currentBaby()?.clientUuid) append(" · 当前")
                    },
                )
                Row {
                    TextButton(onClick = {
                        container.family.switchBaby(b.clientUuid)
                        onChanged()
                    }) { Text("切换") }
                    TextButton(onClick = {
                        container.family.editBabyTheme(b.clientUuid, "#2A9D8F")
                        onChanged()
                    }) { Text("主题色") }
                }
            }
        }
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            label = { Text("新宝宝昵称") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            when (container.family.createOfflineBaby(newName)) {
                is GfResult.Ok -> {
                    newName = ""
                    message = "已添加本机宝宝"
                    onChanged()
                }
                is GfResult.Err -> message = "添加失败"
            }
        }) { Text("添加本机宝宝") }
        if (container.family.isOwner()) {
            Button(onClick = {
                when (container.family.ownerCreateAuthorityBaby(newName.ifBlank { "宝宝" })) {
                    is GfResult.Ok -> {
                        newName = ""
                        message = "已创建家庭权威宝宝"
                        onChanged()
                    }
                    is GfResult.Err -> message = "权限不足或失败"
                }
            }) { Text("管理员创建家庭宝宝") }
        }
        if (message.isNotBlank()) Text(message)
    }
}
