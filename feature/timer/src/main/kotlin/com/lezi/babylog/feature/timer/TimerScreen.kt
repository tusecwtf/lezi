package com.lezi.babylog.feature.timer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TimerRoute() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp)) {
            Text("喂奶计时", style = MaterialTheme.typography.headlineSmall)

        Spacer(Modifier.height(12.dp))
        Text(
            "前台服务计时将在 V1 完善；可先用记录页「母乳」快记。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        }
    }
}
