package com.pairseedkmp.ui.debug.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pairseedkmp.domain.ai.AiMode
import com.pairseedkmp.domain.ai.LlmTask
import com.pairseedkmp.ui.debug.InfoLine
import com.pairseedkmp.ui.debug.SectionCard
import com.pairseedkmp.ui.debug.label

/**
 * 调试面板（07）：现状 / 跑链路 / 提示词与输出 / Provider 配置 / 样例——插在调试壳页尾（同一滚动列）。
 * 形态锁「看得见 + 点得动」；长内容折叠 / 收起，不做样式。
 */
@Composable
fun DebugPanelScreen(panel: DebugPanelState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusCard(panel)
        NowCard(panel)
        RunChainCard(panel)
        PromptCard(panel)
        ProviderCard(panel)
        SampleCard(panel)
    }
}

@Composable
private fun StatusCard(panel: DebugPanelState) {
    SectionCard("面板状态") {
        InfoLine("状态", if (panel.busy) "${panel.status}（运行中…）" else panel.status)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { panel.refresh() }) { Text("刷新现状") }
        }
    }
}

@Composable
private fun NowCard(panel: DebugPanelState) {
    SectionCard("现状（种子池 / 事件）") {
        InfoLine("种子池", "${panel.seeds.size} 颗")
        panel.seeds.take(10).forEach { seed ->
            Text(
                "・${seed.keyword}（主题：${seed.theme}；被挑过 ${seed.pickedCount} 次）",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (panel.seeds.size > 10) {
            Text("…… 还有 ${panel.seeds.size - 10} 颗（种子区块只显示前 10）", style = MaterialTheme.typography.bodySmall)
        }
        InfoLine(
            "事件",
            "清单 ${panel.inListCount} ・ 待确认 ${panel.pendingEvents.size} ・ " +
                "其余 ${panel.events.size - panel.inListCount - panel.pendingEvents.size}（进行中 / 已发生 / 已拒绝）",
        )
        panel.pendingEvents.forEach { event ->
            Text("待确认 #${event.id}：${event.summary}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RunChainCard(panel: DebugPanelState) {
    SectionCard("跑链路（生成 → 查重 → 判定连跑）") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { panel.growSeed() }, enabled = !panel.busy) { Text("长一颗种子") }
            Button(onClick = { panel.growEvent() }, enabled = !panel.busy) { Text("长一条事件") }
        }
        panel.seedRunNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        panel.seedAskContinue?.let { pending ->
            Text(
                "「${pending.toolLabel}」用满上限，重复词：${pending.duplicates.joinToString("、") { it.keyword }.ifEmpty { "无" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { panel.continueSeed() }, enabled = !panel.busy) { Text("继续（下一工具）") }
                Button(onClick = { panel.dropSeedContinue() }) { Text("就此打住") }
            }
        }
        panel.eventReport?.let { report ->
            Text("① 生长：${report.growLine}", style = MaterialTheme.typography.bodySmall)
            report.candidateLines.forEach { Text("② 候选 ・ $it", style = MaterialTheme.typography.bodySmall) }
            report.judgeLines.forEach { Text("③ 判定 ・ $it", style = MaterialTheme.typography.bodySmall) }
            Text("④ 落点：${report.landLine}", style = MaterialTheme.typography.bodySmall)
        }
        Text("提示词与模型原始输出见下方「提示词与输出」区块。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PromptCard(panel: DebugPanelState) {
    SectionCard("提示词与输出") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LlmTask.entries.forEach { task ->
                FilterChip(
                    selected = panel.selectedTask == task,
                    onClick = { panel.selectedTask = task },
                    label = { Text(task.shortLabel()) },
                )
            }
        }
        val task = panel.selectedTask
        val overridden = task in panel.overriddenTasks
        InfoLine(
            "当前（${task.name}）",
            if (overridden) {
                "已覆盖（保存于${if (panel.promptStoreAvailable) "本地文件" else "内存"}；恢复内置可清掉）"
            } else {
                "内置默认"
            },
        )
        OutlinedTextField(
            value = panel.promptDrafts[task].orEmpty(),
            onValueChange = { panel.editPrompt(task, it) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 300.dp),
            textStyle = MaterialTheme.typography.bodySmall,
            label = { Text("系统词（保存后下次调用生效）") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { panel.savePrompt(task) }) { Text("保存覆盖") }
            Button(onClick = { panel.resetPrompt(task) }) { Text("恢复内置") }
        }
        if (panel.overriddenTasks.isNotEmpty()) {
            Text(
                "覆盖中：${panel.overriddenTasks.joinToString("、") { it.name }}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val records = panel.invocationFor(task)
        Text(
            "最近调用（${task.name}）：${records.size} 条 ・ 新的在前"
                + if (task == LlmTask.EVENT_TAG) "；打标段 schema 由程序固化（面板覆盖只动系统词）" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        records.take(6).forEach { invocation -> InvocationRow(invocation) }
    }
}

@Composable
private fun InvocationRow(invocation: LlmInvocation) {
    var expanded by remember(invocation.seq) { mutableStateOf(false) }
    Column {
        Row {
            Text(
                "#${invocation.seq} ・ ${if (invocation.error == null) "成功" else "失败"} ・ " +
                    "${invocation.elapsedMillis}ms" + if (invocation.overridden) " ・ 覆盖" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
        }
        invocation.error?.let { Text("错误：$it", style = MaterialTheme.typography.bodySmall) }
        if (expanded) {
            Text("【系统词】\n${invocation.system}", style = MaterialTheme.typography.bodySmall)
            Text("【输入】\n${invocation.user}", style = MaterialTheme.typography.bodySmall)
            invocation.responseText?.let { Text("【原始输出】\n$it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ProviderCard(panel: DebugPanelState) {
    SectionCard("Provider 配置（模式 / API）") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiMode.entries.forEach { mode ->
                FilterChip(
                    selected = panel.modeDraft == mode,
                    onClick = { panel.editMode(mode) },
                    label = { Text(mode.label()) },
                )
            }
        }
        if (panel.modeDraft == AiMode.ON_DEVICE) {
            Text("端侧未接入（02 spike 遗留）：跑链会提示没有可用实现。", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = panel.baseUrlDraft,
            onValueChange = { panel.editBaseUrl(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API 基址（OpenAI 兼容，不含 /v1）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = panel.modelDraft,
            onValueChange = { panel.editModel(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("模型名") },
            singleLine = true,
        )
        OutlinedTextField(
            value = panel.keyDraft,
            onValueChange = { panel.editKey(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API Key（只进 Keystore，不回显）") },
            placeholder = {
                Text(if (panel.keyConfigured) "已配置（输入新值可替换）" else "未配置")
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        InfoLine("Key 状态", if (panel.keyConfigured) "已配置（Keystore）" else "未配置")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { panel.saveSettings() }) { Text("保存配置") }
            Button(onClick = { panel.saveKey() }, enabled = panel.keyDraft.isNotBlank()) { Text("保存 Key") }
        }
        // 四按钮一行在窄屏会溢出（连接测试被裁到屏外）——拆两行
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { panel.clearKey() }) { Text("清除 Key") }
            Button(onClick = { panel.testConnection() }, enabled = !panel.busy) { Text("连接测试") }
        }
        panel.connectionNote?.let { Text("连接测试：$it", style = MaterialTheme.typography.bodySmall) }
        Text("连接测试 = 按已保存配置发一条最小请求（短超时、不重试）；先「保存配置」再测。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SampleCard(panel: DebugPanelState) {
    SectionCard("判定样例集（一键装载与跑）") {
        Button(onClick = { panel.runSamples() }) { Text("跑样例集（系统三支）") }
        val rows = panel.sampleRows
        if (rows == null) {
            Text("未跑过。跑一遍核对 18 条期望标注（结论 + 理由通道）。", style = MaterialTheme.typography.bodySmall)
        } else {
            InfoLine("结果", "${rows.count { it.ok }}/${rows.size} 一致")
            rows.forEach { row ->
                Text(
                    "${if (row.ok) "✓" else "✗"} ${row.id}【${row.group}】${row.title} ・ " +
                        "${row.expected} → ${row.actual} ・ R ${row.riskText} ・ ${row.cause}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "门槛过 ≤15 / 拒 ≥65；R 与三档带随权重常量走——改一处权重后重跑本区块，裁决带移动直接可见（06 校准）。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun LlmTask.shortLabel(): String = when (this) {
    LlmTask.SEED_GROW -> "种子"
    LlmTask.EVENT_GROW -> "生成"
    LlmTask.EVENT_TAG -> "打标"
    LlmTask.JUDGE -> "判定"
    LlmTask.DEDUP -> "查重"
}
