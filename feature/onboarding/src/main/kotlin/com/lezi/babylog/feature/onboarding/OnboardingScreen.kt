package com.lezi.babylog.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.domain.BootstrapInteractor
import com.lezi.babylog.domain.CreateBabyInput
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val bootstrap: BootstrapInteractor,
) : ViewModel() {
    fun createBaby(nickname: String, onDone: () -> Unit) {
        viewModelScope.launch {
            bootstrap.createBaby(
                CreateBabyInput(
                    nickname = nickname.ifBlank { "宝宝" },
                    birthdayEpochDay = LocalDate.now().toEpochDay(),
                ),
            )
            onDone()
        }
    }
}

@Composable
fun OnboardingRoute(
    onFinished: () -> Unit,
    vm: OnboardingViewModel = hiltViewModel(),
) {
    var name by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(UiTags.ONBOARDING),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("欢迎使用乐记", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("先为宝宝起个昵称，即可开始记录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("昵称") },
            singleLine = true,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { vm.createBaby(name, onFinished) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("开始记录")
        }
    }
}
