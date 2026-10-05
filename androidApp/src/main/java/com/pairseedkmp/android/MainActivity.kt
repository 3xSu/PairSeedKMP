package com.pairseedkmp.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.pairseedkmp.ui.App

class MainActivity : ComponentActivity() {

    private lateinit var holder: ShellHolder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 手动接线（无 DI）：建栈 + 首启自动种（debug 幂等种；release 空实现）；调试壳从 deps 拿全部依赖
        holder = ShellHolder(applicationContext)
        val deps = holder.start()
        setContent {
            App(deps)
        }
    }

    override fun onResume() {
        super.onResume()
        // 打开 / 回前台 = 新会话，空闲重计时（离线空白不累积）；清零数据重来会整条换栈，这里跟着换
        if (::holder.isInitialized) holder.deps.settler.onSessionStart(System.currentTimeMillis())
    }
}
