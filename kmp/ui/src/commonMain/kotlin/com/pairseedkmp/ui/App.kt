package com.pairseedkmp.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pairseedkmp.domain.Greeting
import com.pairseedkmp.ui.theme.PairSeedTheme

/**
 * 应用根界面（共享 UI 入口）：Android 壳只负责挂载它。
 * 后续"最小可视壳"（阶段 2）从这里往里长。
 */
@Composable
fun App() {
    PairSeedTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Text(text = Greeting().greet())
        }
    }
}
