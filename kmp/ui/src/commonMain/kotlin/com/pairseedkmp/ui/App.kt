package com.pairseedkmp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pairseedkmp.ui.debug.DebugShellDeps
import com.pairseedkmp.ui.debug.DebugShellScreen
import com.pairseedkmp.ui.debug.DebugShellState
import com.pairseedkmp.ui.theme.PairSeedTheme

/**
 * 应用根界面（共享 UI 入口）：最小可视壳 = 调试壳单页；Android 壳只负责挂载它。
 * 依赖由壳层（`:androidApp`）手接好传入；"清空数据重来"会换一整条新栈（连库文件一起重建）。
 */
@Composable
fun App(initialDeps: DebugShellDeps) {
    var deps by remember { mutableStateOf(initialDeps) }
    PairSeedTheme {
        val shell = remember(deps) { DebugShellState(deps) }
        LaunchedEffect(shell) { shell.run() }
        DebugShellScreen(
            shell = shell,
            onClearAndReseed = { deps.clearAndReseed?.invoke()?.let { deps = it } },
        )
    }
}
