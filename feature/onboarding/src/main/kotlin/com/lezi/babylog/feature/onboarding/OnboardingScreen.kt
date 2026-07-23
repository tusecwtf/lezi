package com.lezi.babylog.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CreateBabyInput
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.launch

private val ThemePalette = listOf(
    0xFF007BAE.toInt(),
    0xFFAA442B.toInt(),
    0xFF2F8F6B.toInt(),
    0xFF7A5CFF.toInt(),
    0xFFE09F3E.toInt(),
    0xFFD4578C.toInt(),
    0xFF4C6A92.toInt(),
    0xFF5B8C5A.toInt(),
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val careLog: CareLog,
) : ViewModel() {
    fun createBaby(
        nickname: String,
        sex: String?,
        birthdayEpochDay: Long,
        themeColorArgb: Int,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                careLog.createBaby(
                    CreateBabyInput(
                        nickname = nickname.trim(),
                        sex = sex,
                        birthdayEpochDay = birthdayEpochDay,
                        themeColorArgb = themeColorArgb,
                    ),
                )
                onDone()
            } catch (t: Throwable) {
                Log.e("Onboarding", "createBaby failed", t)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingRoute(
    onFinished: () -> Unit,
    vm: OnboardingViewModel = hiltViewModel(),
) {
    var name by remember { mutableStateOf("年年") }
    var sex by remember { mutableStateOf<String?>(null) }
    var birthday by remember { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var themeIdx by remember { mutableIntStateOf(0) }
    var showDate by remember { mutableStateOf(false) }
    var showJoinStub by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf(false) }
    val dateLabel = remember(birthday) {
        LocalDate.ofEpochDay(birthday).format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag(UiTags.ONBOARDING),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("欢迎使用乐记", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "创建宝宝档案后即可开始记录。无需登录。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                nameError = false
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("昵称") },
            isError = nameError,
            supportingText = if (nameError) {
                { Text("请填写昵称") }
            } else {
                null
            },
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))
        Text("性别", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("MALE" to "男", "FEMALE" to "女", "UNKNOWN" to "保密").forEach { (key, label) ->
                FilterChip(
                    selected = sex == key,
                    onClick = { sex = key },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("生日", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showDate = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(dateLabel)
        }
        Spacer(Modifier.height(16.dp))
        Text("主题色", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ThemePalette.forEachIndexed { index, argb ->
                val selected = themeIdx == index
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .then(
                            if (selected) {
                                Modifier.border(3.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickable { themeIdx = index },
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {
                if (name.isBlank()) {
                    nameError = true
                    return@Button
                }
                vm.createBaby(
                    nickname = name,
                    sex = sex,
                    birthdayEpochDay = birthday,
                    themeColorArgb = ThemePalette[themeIdx],
                    onDone = onFinished,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("开始记录")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { showJoinStub = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("加入家庭")
        }
    }

    if (showDate) {
        val initialMillis = LocalDate.ofEpochDay(birthday)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { ms ->
                            birthday = Instant.ofEpochMilli(ms)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                                .toEpochDay()
                        }
                        showDate = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (showJoinStub) {
        AlertDialog(
            onDismissRequest = { showJoinStub = false },
            title = { Text("加入家庭") },
            text = { Text("同步将在后续版本提供") },
            confirmButton = {
                TextButton(onClick = { showJoinStub = false }) { Text("知道了") }
            },
        )
    }
}
