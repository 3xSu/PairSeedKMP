package com.pairseedkmp.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pairseedkmp.domain.common.MILLIS_PER_MINUTE
import com.pairseedkmp.domain.common.MILLIS_PER_SECOND
import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.event.ConfirmationAnswer
import com.pairseedkmp.domain.event.DEFAULT_IDLE_TRIGGER_THRESHOLD_MILLIS
import com.pairseedkmp.domain.event.Event
import com.pairseedkmp.domain.event.EventOrigin
import com.pairseedkmp.domain.event.EventStatus
import com.pairseedkmp.domain.event.RunningEvent
import com.pairseedkmp.domain.event.TriggerDecision
import com.pairseedkmp.domain.event.durationSeconds
import com.pairseedkmp.ui.debug.panel.DebugPanelScreen
import com.pairseedkmp.ui.debug.panel.DebugPanelState
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 调试壳单页：事件列表 / 此刻面板 / 操作区——形态锁死"看得见 + 点得动"，
 * 不做样式 / 动画 / 历史页（计划步骤 6）。
 * [panel] != null 时页尾追加调试面板（07：现状 / 跑链路 / 提示词 / Provider / 样例）。
 */
@Composable
fun DebugShellScreen(
    shell: DebugShellState,
    onClearAndReseed: () -> Unit,
    panel: DebugPanelState? = null,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Header(shell)
            NowCard(shell)
            TriggerCard(shell)
            ActionsCard(shell, onClearAndReseed)
            EventsCard(shell)
            panel?.let { DebugPanelScreen(it) }
        }
    }
}

@Composable
private fun Header(shell: DebugShellState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("PairSeedKMP · 调试壳", style = MaterialTheme.typography.titleLarge)
        val ctx = shell.context
        Text(
            text = if (ctx != null) {
                "角色组：${ctx.packName} ・ ${ctx.actorName}${ctx.targetName?.let { " ↔ $it" } ?: ""} ・ 场景：${ctx.sceneName}"
            } else {
                "没有角色组上下文（空库 / release 不种）"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (!shell.debugEnabled) {
            Text("release：调试通道关闭（不种、不能清空重来）", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NowCard(shell: DebugShellState) {
    SectionCard("此刻") {
        InfoLine("现在", shell.clock(shell.now))
        val settle = shell.settle
        val idleSince = settle?.idleSince
        val idleText = when {
            settle == null -> "—（还没结算过）"
            idleSince == null -> "有事件在跑 → 不空闲"
            else -> "已空闲 ${formatDuration(settle.now - idleSince)}（满 ${
                DEFAULT_IDLE_TRIGGER_THRESHOLD_MILLIS / MILLIS_PER_MINUTE
            } 分钟才从清单挑）"
        }
        InfoLine("空闲", idleText)
        InfoLine("上次在场", settle?.previousLastSeenAt?.let(shell::clock) ?: "无记录（首次启动）")
        InfoLine(
            "本次结算",
            if (settle == null) "—" else "完成 ${settle.finished.size} 条 ・ 过期滚出 ${settle.expired.size} 条",
        )
        val running = settle?.running.orEmpty()
        if (running.isEmpty()) {
            InfoLine("进行中", "无")
        } else {
            running.forEach { r -> InfoLine("进行中", runningLine(shell, r)) }
        }
    }
}

private fun runningLine(shell: DebugShellState, running: RunningEvent): String {
    val steps = running.event.steps
    val pos = running.position
    val step = steps.getOrNull(pos.index)
        ?: return "#${running.event.id} ${running.event.summary}（步骤越界）"
    val sayText = step.params["text"]?.jsonPrimitive?.contentOrNull
    val stepTotal = step.durationSeconds(shell.durations)
    val elapsedSec = pos.elapsedInStepMillis / MILLIS_PER_SECOND
    return "#${running.event.id} ${running.event.summary} ・ 第 ${pos.index + 1}/${steps.size} 步：" +
        "${step.action}${sayText?.let { "「$it」" } ?: ""}（本步 ${elapsedSec}s / ${stepTotal}s）"
}

@Composable
private fun TriggerCard(shell: DebugShellState) {
    SectionCard("触发检查（每秒一次）") {
        val trigger = shell.trigger
        InfoLine("判断", trigger?.decision?.label() ?: "—（还没有角色组上下文）")
        shell.lastFiredNote?.let { InfoLine("最近发生", it) }
        shell.swallowedNote?.let { InfoLine("离线翻篇", it) }
    }
}

@Composable
private fun ActionsCard(shell: DebugShellState, onClearAndReseed: () -> Unit) {
    SectionCard("操作区") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleEvent.entries.forEach { sample ->
                Button(
                    onClick = { shell.insertSample(sample) },
                    enabled = shell.context != null,
                ) { Text(sample.buttonLabel) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { shell.manualSettle() }) { Text("手动结算一次") }
            Button(
                onClick = { shell.autoGrowNow() },
                enabled = shell.context != null && !shell.autoBusy,
            ) { Text("跑·自动链") }
            Button(onClick = onClearAndReseed, enabled = shell.canReset) { Text("清空数据重来") }
        }
        shell.autoStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("上次操作：${shell.feedback}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EventsCard(shell: DebugShellState) {
    SectionCard("事件列表（${shell.events.size} 条 ・ 新的在前；逻辑删除的不显示）") {
        if (shell.events.isEmpty()) {
            Text("空", style = MaterialTheme.typography.bodySmall)
        }
        shell.events.forEachIndexed { index, event ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            EventRow(shell, event)
        }
    }
}

@Composable
private fun EventRow(shell: DebugShellState, event: Event) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("#${event.id}【${event.status.label()}】${event.summary}", style = MaterialTheme.typography.bodyMedium)
        Text(eventMeta(event), style = MaterialTheme.typography.bodySmall)
        Text(
            "发生：${event.occurredAt?.let(shell::clock) ?: "—"} ・ 过期：${shell.stamp(event.expiresAt)}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (shell.canAnswer(event.id)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { shell.answer(event.id, ConfirmationAnswer.YES) }) { Text("答是") }
                Button(onClick = { shell.answer(event.id, ConfirmationAnswer.NO) }) { Text("答否") }
            }
        } else if (event.status in ANSWERABLE_STATUSES) {
            Text("（门没过：现在不在可答时段 / 已过期）", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 可答状态集（与《工作链路》可答门一致；这里只用来决定"门没过"提示显不显示）。 */
private val ANSWERABLE_STATUSES = setOf(
    EventStatus.PENDING_CONFIRMATION,
    EventStatus.IN_LIST,
    EventStatus.REJECTED,
)

private fun eventMeta(event: Event): String = buildString {
    if (event.suitableTime.isNotEmpty()) {
        append("时段：${event.suitableTime.joinToString("、") { it.label() }} ・ ")
    }
    append("来源：${event.origin.label()} ・ 时长：${formatDuration(event.durationSec * MILLIS_PER_SECOND)}")
}

@Composable
internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun InfoLine(label: String, value: String) {
    Row {
        Text("$label：", style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

internal fun Period.label(): String = when (this) {
    Period.MORNING -> "早上"
    Period.AFTERNOON -> "下午"
    Period.EVENING -> "晚上"
    Period.NIGHT -> "深夜"
}

internal fun EventStatus.label(): String = when (this) {
    EventStatus.PENDING_CONFIRMATION -> "待确认"
    EventStatus.IN_LIST -> "在清单"
    EventStatus.IN_PROGRESS -> "进行中"
    EventStatus.OCCURRED -> "已发生"
    EventStatus.REJECTED -> "已拒绝"
}

internal fun EventOrigin.label(): String = when (this) {
    EventOrigin.SEED -> "种子"
    EventOrigin.SCHEDULED -> "半固定"
    EventOrigin.PRESET -> "预置"
}

internal fun TriggerDecision.label(): String = when (this) {
    TriggerDecision.BLOCKED_BY_RUNNING -> "有事件在跑：不打断"
    TriggerDecision.FIRED_SEMI_FIXED_OVERDUE -> "半固定·过最晚：补发"
    TriggerDecision.FIRED_CONTINUATION -> "接续：刚结束的指认兑现"
    TriggerDecision.FIRED_SEMI_FIXED_IN_WINDOW -> "半固定·窗口内：发生"
    TriggerDecision.FIRED_FROM_LIST -> "空闲挑清单：发生"
    TriggerDecision.IDLE_BELOW_THRESHOLD -> "空闲没到阈值：继续攒"
    TriggerDecision.IDLE_NO_ELIGIBLE_EVENT -> "空闲够了：清单里没有含当前时段的事件"
}

internal fun formatDuration(millis: Long): String {
    val seconds = millis / MILLIS_PER_SECOND
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}
