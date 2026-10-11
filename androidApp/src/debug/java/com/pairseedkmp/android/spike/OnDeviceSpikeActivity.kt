package com.pairseedkmp.android.spike

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.pairseedkmp.domain.ai.LlmJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * 节点 02 spike：端侧模型最小验证页（debug 专属）。
 * 指标：加载时长 / 每轮耗时 / JSON 成功率（[LlmJson]）/ 判定正确率 / 进程 PSS。
 * v3（2026-10-10）：生成提示词锚点实验——两维闭集 1-4 + 定义锚点 / 无锚对照 / enum 探针（06 待验证项）。
 * v3.1（2026-10-10）：生成形态对齐——五字段三层说明（定义/用途/依据）+ steps/「说」台词落点；after 不产出
 *   （接续改运行时）；单段 vs 生成+打分两段对照。
 * 自动化：`adb shell am start -n com.pairseedkmp.android/com.pairseedkmp.android.spike.OnDeviceSpikeActivity --es mode all|allv3|v3a|v3b|v3c|allv31|v31a|v31b`
 * 输出同时进 logcat（tag=PS_SPIKE，行前缀 [spike]）。
 */
class OnDeviceSpikeActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var running = false

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        val mode = intent.getStringExtra("mode")
        if (mode != null) {
            // 预留自动跑：等 UI 挂载后执行
            logView.post { autoRun(mode) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        engine?.close()
        engine = null
        super.onDestroy()
    }

    // ---------- UI ----------

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val title = TextView(this).apply {
            text = "端侧模型验证（Qwen3-0.6B / litertlm 0.17.1）"
            textSize = 16f
        }
        root.addView(title)

        logView = TextView(this).apply {
            textSize = 11f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(scroll)

        val rows: List<Pair<String, suspend () -> Unit>> = listOf(
            "模型文件" to { checkModel() },
            "加载模型" to { loadModel() },
            "生成×1" to { runGen(1, constrained = true, dump = true) },
            "生成×10 约束" to { runGen(10, constrained = true, dump = false) },
            "生成×10 自由" to { runGen(10, constrained = false, dump = false) },
            "v3锚点×10" to { runGen3("anchor", 10) },
            "v3无锚×10" to { runGen3("plain", 10) },
            "v3enum×5" to { runGen3("enum", 5) },
            "v3.1单段×10" to { runGen31("single", 10) },
            "v3.1两段×10" to { runGen31("two", 10) },
            "判定×10 约束" to { runJudge(10) },
            "内存" to { logMem("manual") },
            "清空" to { logView.text = "" },
        )
        rows.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (label, action) ->
                val b = Button(this).apply {
                    text = label
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { launchJob(label, action) }
                }
                row.addView(b)
            }
            root.addView(row)
        }
        return root
    }

    private fun launchJob(label: String, action: suspend () -> Unit) {
        if (running) {
            Toast.makeText(this, "上一任务还在跑", Toast.LENGTH_SHORT).show()
            return
        }
        running = true
        scope.launch {
            try {
                action()
            } catch (t: Throwable) {
                log("ERROR phase=$label msg=${t.javaClass.simpleName}: ${t.message}")
                Log.e(TAG, "ERROR phase=$label", t)
            } finally {
                running = false
            }
        }
    }

    private fun autoRun(mode: String) {
        when (mode) {
            "file" -> launchJob(mode) { checkModel() }
            "load" -> launchJob(mode) { loadModel() }
            "gen1" -> launchJob(mode) { loadIfNeeded(); runGen(1, constrained = true, dump = true) }
            "gen10" -> launchJob(mode) { loadIfNeeded(); runGen(10, constrained = true, dump = false) }
            "gen10free" -> launchJob(mode) { loadIfNeeded(); runGen(10, constrained = false, dump = false) }
            "judge10" -> launchJob(mode) { loadIfNeeded(); runJudge(10) }
            "v3a" -> launchJob(mode) { runGen3("anchor", 10) }
            "v3b" -> launchJob(mode) { runGen3("plain", 10) }
            "v3c" -> launchJob(mode) { runGen3("enum", 5) }
            "v31a" -> launchJob(mode) { runGen31("single", 10) }
            "v31b" -> launchJob(mode) { runGen31("two", 10) }
            "all" -> launchJob(mode) {
                checkModel()
                loadModel()
                runGen(1, constrained = true, dump = true)
                runGen(10, constrained = true, dump = false)
                runJudge(10)
                logMem("final")
            }
            "allv3" -> launchJob(mode) {
                checkModel()
                loadModel()
                runGen3("anchor", 10)
                runGen3("plain", 10)
                runGen3("enum", 5)
                logMem("final")
            }
            "allv31" -> launchJob(mode) {
                checkModel()
                loadModel()
                runGen31("single", 10)
                runGen31("two", 10)
                logMem("final")
            }
            else -> log("未知 mode=$mode")
        }
    }

    // ---------- 步骤 ----------

    /** 模型文件名可用 `--es model <文件名>` 覆盖（默认仍是 0.6B spike 模型）。 */
    private val modelName: String by lazy { intent.getStringExtra("model") ?: "Qwen3-0.6B.litertlm" }

    private fun modelFile(): File = File(getExternalFilesDir(null), modelName)

    private fun checkModel() {
        val f = modelFile()
        log("model exists=${f.exists()} bytes=${f.length()} path=${f.absolutePath}")
    }

    private fun loadIfNeeded() {
        if (engine == null) loadModel()
    }

    private fun loadModel() {
        val f = modelFile()
        if (!f.exists()) {
            log("ERROR phase=load msg=model not found at ${f.absolutePath}")
            return
        }
        logMem("beforeLoad")
        val t0 = SystemClock.elapsedRealtime()
        // initialize() 是阻塞调用：本方法已在 Dispatchers.IO 协程里（官方建议勿占 UI 线程）
        val e = Engine(
            EngineConfig(
                modelPath = f.absolutePath,
                backend = Backend.CPU(),
                cacheDir = cacheDir.absolutePath,
            )
        )
        e.initialize()
        val ms = SystemClock.elapsedRealtime() - t0
        engine = e
        log("load ok ms=$ms initialized=${e.isInitialized()}")
        logMem("afterLoad")
    }

    private class RunResult(val text: String, val ms: Long, val chunks: Int)

    private suspend fun completeOnce(
        system: String,
        user: String,
        schema: String?,
        temperature: Double,
        maxOut: Int,
        constrained: Boolean,
        seed: Int,
    ): RunResult {
        val e = engine ?: throw IllegalStateException("engine not loaded")
        val t0 = SystemClock.elapsedRealtime()
        var chunks = 0
        val sb = StringBuilder()
        e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature, seed = seed),
                maxOutputToken = maxOut,
                thinkingConfig = ThinkingConfig(enableThinking = false),
                enableResponseFormat = constrained,
            )
        ).use { conv ->
            val flow = if (constrained && schema != null) {
                conv.sendMessageAsync(user, responseFormat = ResponseFormat.json(schema))
            } else {
                conv.sendMessageAsync(user)
            }
            flow.collect { msg ->
                chunks++
                sb.append(msg.toString())
            }
        }
        return RunResult(sb.toString(), SystemClock.elapsedRealtime() - t0, chunks)
    }

    private class WhyResult(val text: String, val ms: Long, val chunks: Int, val why: String?, val whyMs: Long)

    // A2 专用：约束 JSON 一次；若 verbal_directness 为 null，在同一会话里追问原因（自由文本，诊断用，不计入主耗时）。
    private suspend fun completeA2WithWhy(
        system: String,
        user: String,
        schema: String,
        temperature: Double,
        maxOut: Int,
        seed: Int,
        whyQuestion: String,
    ): WhyResult {
        val e = engine ?: throw IllegalStateException("engine not loaded")
        val t0 = SystemClock.elapsedRealtime()
        var chunks = 0
        val sb = StringBuilder()
        return e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature, seed = seed),
                maxOutputToken = maxOut,
                thinkingConfig = ThinkingConfig(enableThinking = false),
                enableResponseFormat = true,
            )
        ).use { conv ->
            conv.sendMessageAsync(user, responseFormat = ResponseFormat.json(schema)).collect { msg ->
                chunks++
                sb.append(msg.toString())
            }
            val msFirst = SystemClock.elapsedRealtime() - t0
            var why: String? = null
            var whyMs = 0L
            if (verbalIsNull(sb.toString())) {
                // 同会话追问会被上一个 JSON 格式锁住（实测复读原 JSON）→ 独立不约束会话重述上下文再问。
                val t1 = SystemClock.elapsedRealtime()
                val whyUser = buildString {
                    append(user)
                    append("\n\n你刚才的回答：\n")
                    append(sb.toString())
                    append("\n\n追问：")
                    append(whyQuestion)
                }
                val sb2 = StringBuilder()
                e.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(GEN31_A2_WHY_SYSTEM),
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3, seed = seed + 9000),
                        maxOutputToken = 256,
                        thinkingConfig = ThinkingConfig(enableThinking = false),
                        enableResponseFormat = false,
                    )
                ).use { c2 ->
                    c2.sendMessageAsync(whyUser).collect { msg -> sb2.append(msg.toString()) }
                }
                why = sb2.toString().trim()
                whyMs = SystemClock.elapsedRealtime() - t1
            }
            WhyResult(sb.toString(), msFirst, chunks, why, whyMs)
        }
    }

    private fun verbalIsNull(text: String): Boolean {
        val p = LlmJson.parse(text) ?: return false
        val ax = p["comparison_axes"] as? JsonObject ?: return false
        return ax["verbal_directness"] is JsonNull
    }

    private class RefillResult(val text: String, val ms: Long, val reasoning: String, val reasoningMs: Long)

    // 反思系统词：把"直接输出 JSON"改写成"先答问题、收到指令后再输出 JSON"。
    private fun reflectSystem(): String = GEN31_A2_SYSTEM.replace(
        "输出：严格按 comparison_axes → suitable_time 的顺序生成 JSON，不要任何解释文字。",
        "注意：照用户消息的指示行动——用户会先请你说清楚复查结论，之后才让你重新输出 JSON，那时再严格按 comparison_axes → suitable_time 的顺序输出。",
    )

    // 反思变体 2：同一会话两问——先自由文本"回答"（台词有无 + 该填什么），再据此"重填" JSON。
    private suspend fun completeReflectRefill(
        digest: String,
        prevAnswer: String,
        problem: String?,
        seed: Int,
    ): RefillResult {
        val e = engine ?: throw IllegalStateException("engine not loaded")
        return e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(reflectSystem()),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.2, seed = seed),
                maxOutputToken = 768,
                thinkingConfig = ThinkingConfig(enableThinking = false),
                enableResponseFormat = true,
            )
        ).use { conv ->
            val ask = buildString {
                appendLine("你（同一个打标助手）刚才处理过下面这条事件，并给出了一个回答。")
                appendLine("注意：下面那段 JSON 是**你自己**（你本人）写的回答，不是别人填的——请把它当作你自己的答案来检查。")
                appendLine()
                append(digest)
                appendLine()
                appendLine("【你自己的回答（你刚才写的）】：")
                appendLine(prevAnswer)
                appendLine()
                appendLine("复查依据（重申）：「台词」块里有句子 → verbal_directness 必须按台词的说法在 1-4 里选（1 绕着说、只说一半；2 话留白、要对方领会；3 基本直说、少有修饰；4 想什么说什么）；只有「台词」块写着「没有」时才填 null。emotion_visibility：看动作顺序和台词整体给人的感觉，1 看不出 → 4 全写在脸上。suitable_time：按事件性质填，拿不准就填 []。")
                if (problem != null) appendLine("程序已核对：$problem。")
                append("现在先回答我两个问题（用一两句话，直接回答，不要输出 JSON）：①「台词」块里有没有句子？②因此 verbal_directness 应该填 null，还是 1-4 里的某个数？如果填数，填哪个、为什么？")
            }
            val t0 = SystemClock.elapsedRealtime()
            val sr = StringBuilder()
            conv.sendMessageAsync(ask).collect { msg -> sr.append(msg.toString()) }
            val reasoningMs = SystemClock.elapsedRealtime() - t0
            val t1 = SystemClock.elapsedRealtime()
            val sj = StringBuilder()
            conv.sendMessageAsync(
                "好。现在请按你刚才复查的结论，重新输出完整的 comparison_axes 和 suitable_time（只输出 JSON）。",
                responseFormat = ResponseFormat.json(GEN31_A2_SCHEMA),
            ).collect { msg -> sj.append(msg.toString()) }
            RefillResult(sj.toString(), SystemClock.elapsedRealtime() - t1, sr.toString().trim(), reasoningMs)
        }
    }

    private suspend fun runGen(times: Int, constrained: Boolean, dump: Boolean) {
        loadIfNeeded()
        var ok = 0
        var msSum = 0L
        var charsSum = 0
        repeat(times) { i ->
            try {
                val r = completeOnce(
                    system = GEN_SYSTEM,
                    user = GEN_USER,
                    schema = GEN_SCHEMA,
                    temperature = 0.7,
                    maxOut = 512,
                    constrained = constrained,
                    seed = 1000 + i,
                )
                val parsed = LlmJson.parse(r.text)
                val okNow = parsed != null
                if (okNow) ok++
                msSum += r.ms
                charsSum += r.text.length
                log(
                    "RUN task=gen${if (constrained) "C" else "F"} i=${i + 1}/$times ms=${r.ms} " +
                        "chars=${r.text.length} chunks=${r.chunks} jsonOk=$okNow pssMb=${pssMb()}"
                )
                if (dump || !okNow) log("GEN TEXT #${i + 1}: ${r.text}")
            } catch (t: Throwable) {
                log("RUN task=gen${if (constrained) "C" else "F"} i=${i + 1}/$times ERROR ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        log(
            "SUMMARY task=gen${if (constrained) "C" else "F"} jsonOk=$ok/${times} " +
                "avgMs=${if (times > 0) msSum / times else 0} avgChars=${charsSum / times}"
        )
        logMem("afterGen")
    }

    /**
     * v3 锚点实验（2026-10-10）：变体 anchor（两维 + 定义锚点）/ plain（无锚对照）/ enum（enum 探针，同 anchor 文案）。
     * 3 条样本轮换（i % size）；日志直接打两维取值与 rangeOk，SUMMARY 打取值分布。
     */
    private suspend fun runGen3(variant: String, times: Int) {
        loadIfNeeded()
        val (system, schema) = when (variant) {
            "anchor" -> GEN3_ANCHOR_SYSTEM to GEN3_SCHEMA
            "plain" -> GEN3_PLAIN_SYSTEM to GEN3_SCHEMA
            "enum" -> GEN3_ANCHOR_SYSTEM to GEN3_SCHEMA_ENUM
            else -> throw IllegalArgumentException("unknown v3 variant=$variant")
        }
        var ok = 0
        var rangeOk = 0
        var msSum = 0L
        val tallyV = mutableMapOf<String, Int>()
        val tallyE = mutableMapOf<String, Int>()
        repeat(times) { i ->
            val s = i % GEN3_SAMPLES.size
            try {
                val r = completeOnce(
                    system = system,
                    user = GEN3_SAMPLES[s],
                    schema = schema,
                    temperature = 0.7,
                    maxOut = 512,
                    constrained = true,
                    seed = 1000 + i,
                )
                val parsed = LlmJson.parse(r.text)
                val okNow = parsed != null
                if (okNow) ok++
                val axes = parsed?.get("comparison_axes") as? JsonObject
                val vEl = axes?.get("verbal_directness")
                val eEl = axes?.get("emotion_visibility")
                val v = when {
                    axes == null -> "noaxes"
                    vEl == null -> "missing"
                    vEl is JsonNull -> "null"
                    else -> vEl.toString()
                }
                val e = eEl?.toString() ?: "missing"
                val vNum = v.trim('"').toIntOrNull()
                val eNum = e.trim('"').toIntOrNull()
                val rangeNow = okNow &&
                    (v == "null" || (vNum != null && vNum in 1..4)) &&
                    (eNum != null && eNum in 1..4)
                if (rangeNow) rangeOk++
                tallyV[v] = (tallyV[v] ?: 0) + 1
                tallyE[e] = (tallyE[e] ?: 0) + 1
                msSum += r.ms
                log(
                    "RUN task=gen3-$variant sample=${s + 1} i=${i + 1}/$times ms=${r.ms} " +
                        "chars=${r.text.length} jsonOk=$okNow verbal=$v emotion=$e rangeOk=$rangeNow"
                )
                log("GEN3 TEXT #${i + 1} s=${s + 1}: ${r.text}")
            } catch (t: Throwable) {
                log(
                    "RUN task=gen3-$variant sample=${s + 1} i=${i + 1}/$times " +
                        "ERROR ${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }
        log(
            "SUMMARY task=gen3-$variant jsonOk=$ok/$times rangeOk=$rangeOk/$times " +
                "avgMs=${if (times > 0) msSum / times else 0} verbal=${tallyV.toSortedMap()} emotion=${tallyE.toSortedMap()}"
        )
        logMem("afterGen3-$variant")
    }

    /**
     * v3.1 形态对齐实验（2026-10-10）：V1 single（五字段一次调用）vs V2 two（A1 生成 → A2 打分）。
     * 三层说明（定义/用途/依据）；steps/「说」台词落点；after 不产出（接续移到运行时）。
     * 统计：jsonOk / rangeOk / hasSay（写了台词的轮数）/ 两维与时段分布 / 分阶段耗时。
     */
    private suspend fun runGen31(variant: String, times: Int) {
        loadIfNeeded()
        var ok = 0
        var rangeOk = 0
        var hasSay = 0
        var reflectFired = 0
        var reflectFixed = 0
        var reflectBroke = 0
        var reflectChanged = 0
        var msSum = 0L
        val tallyV = mutableMapOf<String, Int>()
        val tallyE = mutableMapOf<String, Int>()
        val tallyT = mutableMapOf<String, Int>()
        fun tally(map: MutableMap<String, Int>, k: String) {
            map[k] = (map[k] ?: 0) + 1
        }
        repeat(times) { i ->
            val s = i % GEN31_SAMPLES.size
            try {
                if (variant == "single") {
                    val r = completeOnce(
                        system = GEN31_SYSTEM, user = GEN31_SAMPLES[s], schema = GEN31_SCHEMA,
                        temperature = 0.7, maxOut = 768, constrained = true, seed = 3100 + i,
                    )
                    val parsed = LlmJson.parse(r.text)
                    val okNow = parsed != null
                    if (okNow) ok++
                    val say = countSay(parsed?.get("steps"))
                    if (say > 0) hasSay++
                    val axes = parsed?.get("comparison_axes") as? JsonObject
                    val v = cellOf(axes?.get("verbal_directness"))
                    val e = cellOf(axes?.get("emotion_visibility"))
                    val t = parsed?.get("suitable_time")?.toString() ?: "missing"
                    val rangeNow = okNow &&
                        (v == "null" || (v.trim('"').toIntOrNull() ?: 0) in 1..4) &&
                        (e.trim('"').toIntOrNull() ?: 0) in 1..4
                    if (rangeNow) rangeOk++
                    tally(tallyV, v); tally(tallyE, e); tally(tallyT, t)
                    msSum += r.ms
                    log(
                        "RUN task=gen31-single sample=${s + 1} i=${i + 1}/$times ms=${r.ms} " +
                            "chars=${r.text.length} jsonOk=$okNow rangeOk=$rangeNow say=$say verbal=$v emotion=$e time=$t"
                    )
                    log("GEN31 S TEXT #${i + 1} s=${s + 1}: ${r.text}")
                } else {
                    val r1 = completeOnce(
                        system = GEN31_A1_SYSTEM, user = GEN31_SAMPLES[s], schema = GEN31_A1_SCHEMA,
                        temperature = 0.7, maxOut = 768, constrained = true, seed = 3100 + i,
                    )
                    val p1 = LlmJson.parse(r1.text)
                    log("GEN31 A1 TEXT #${i + 1} s=${s + 1}: ${r1.text}")
                    if (p1 == null) {
                        msSum += r1.ms
                        tally(tallyV, "a1fail")
                        log("RUN task=gen31-two sample=${s + 1} i=${i + 1}/$times ms=${r1.ms} a1Ok=false skipA2")
                        return@repeat
                    }
                    val say = countSay(p1.get("steps"))
                    if (say > 0) hasSay++
                    // 方案 A：A2 输入为程序整理的平铺文字（台词逐条抽出），不再让模型自己翻 JSON 找「说」步骤。
                    val says = sayLines(p1.get("steps"))
                    val digest = renderEventDigest(p1)
                    log("GEN31 A2 DIGEST #${i + 1} s=${s + 1}: ${digest.replace("\n", " | ")}")
                    // A2 打标：标注任务用 0.2；种子基 5100（与④⑤轮同种子，首答可对照）/ A′ 复现轮用 6100。
                    // A′：verbal_directness 的可选项由程序对「台词」块的确定性判断直接固化进 schema。
                    val a2Schema = gen31A2Schema(says)
                    val r2 = completeOnce(
                        system = GEN31_A2_SYSTEM, user = digest, schema = a2Schema,
                        temperature = 0.2, maxOut = 768, constrained = true, seed = 6100 + i,
                    )
                    var p2 = LlmJson.parse(r2.text)
                    val p2First = p2
                    var a2Text = r2.text
                    var a2Ms = r2.ms
                    log("GEN31 A2 TEXT #${i + 1} s=${s + 1}: $a2Text")
                    // 反喂反问：每例保底复查一轮（事件+它自己的回答喂回去）；复查后仍不一致则第 2 轮定向纠错。
                    // A′ 轮关闭（GEN31_REFLECT_ENABLED=false）：只测 schema 固化本身。
                    if (p2 != null && GEN31_REFLECT_ENABLED) {
                        var problem = inconsistency(p2, says)
                        var round = 0
                        while (round < 2) {
                            round++
                            reflectFired++
                            val hadProblem = problem != null
                            val bv = verbalCell(p2)
                            // 反思变体 2：同一会话两问——先"回答"（自由文本：台词有无+该填什么），再据此"重填" JSON。
                            val rr = completeReflectRefill(digest, a2Text, problem, seed = 7000 + i * 10 + round)
                            a2Ms += rr.reasoningMs + rr.ms
                            log(
                                "GEN31 A2 REFLECT-ANSWER #${i + 1} s=${s + 1} round=$round ms=${rr.reasoningMs}: " +
                                    rr.reasoning.replace("\n", " | ")
                            )
                            val np = LlmJson.parse(rr.text)
                            if (np == null) {
                                log("GEN31 A2 REFLECT #${i + 1} s=${s + 1} round=$round ms=${rr.reasoningMs}+${rr.ms} jsonFail")
                                break
                            }
                            a2Text = rr.text
                            p2 = np
                            val after = inconsistency(np, says)
                            log(
                                "GEN31 A2 REFLECT #${i + 1} s=${s + 1} round=$round ms=${rr.reasoningMs}+${rr.ms} " +
                                    "bv=$bv av=${verbalCell(np)} before=${problem ?: "无（保底复查）"} after=${after ?: "consistent"}"
                            )
                            if (after == null) {
                                if (hadProblem) reflectFixed++
                                break
                            }
                            if (!hadProblem) reflectBroke++
                            problem = after
                        }
                        // 值级比较（§11 教训：文本比较会把空白/格式差异当成改动）。
                        val firstSig = answerSig(p2First)
                        if (firstSig != null && answerSig(p2) != firstSig) reflectChanged++
                    }
                    val okNow = p2 != null
                    if (okNow) ok++
                    val axes = p2?.get("comparison_axes") as? JsonObject
                    val v = cellOf(axes?.get("verbal_directness"))
                    val e = cellOf(axes?.get("emotion_visibility"))
                    val t = p2?.get("suitable_time")?.toString() ?: "missing"
                    val rangeNow = okNow &&
                        (v == "null" || (v.trim('"').toIntOrNull() ?: 0) in 1..4) &&
                        (e.trim('"').toIntOrNull() ?: 0) in 1..4
                    if (rangeNow) rangeOk++
                    tally(tallyV, v); tally(tallyE, e); tally(tallyT, t)
                    msSum += r1.ms + a2Ms
                    log(
                        "RUN task=gen31-two sample=${s + 1} i=${i + 1}/$times ms=${r1.ms}+$a2Ms " +
                            "a1Ok=true a2Ok=$okNow rangeOk=$rangeNow say=$say verbal=$v emotion=$e time=$t"
                    )
                    if (answerSig(p2) != answerSig(p2First)) log("GEN31 A2 FINAL #${i + 1} s=${s + 1}: $a2Text")
                }
            } catch (t: Throwable) {
                log(
                    "RUN task=gen31-$variant sample=${s + 1} i=${i + 1}/$times " +
                        "ERROR ${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }
        log(
            "SUMMARY task=gen31-$variant jsonOk=$ok/$times rangeOk=$rangeOk/$times hasSay=$hasSay/$times " +
                "reflect=$reflectFired/$times fixed=$reflectFixed broke=$reflectBroke changed=$reflectChanged " +
                "avgMs=${if (times > 0) msSum / times else 0} verbal=${tallyV.toSortedMap()} " +
                "emotion=${tallyE.toSortedMap()} time=${tallyT.toSortedMap()}"
        )
        logMem("afterGen31-$variant")
    }

    private fun countSay(steps: JsonElement?): Int {
        val arr = steps as? JsonArray ?: return -1
        return arr.count { (it as? JsonObject)?.get("action")?.toString() == "\"say\"" }
    }

    // 方案 A：把 A1 产物整理成平铺文字（概要 / 动作顺序 / 台词逐条抽出），A2 不再自己翻 JSON 找「说」步骤。
    private fun renderEventDigest(e: JsonObject): String {
        val sb = StringBuilder()
        sb.appendLine("下面是一条已经生成好的事件（已整理成文字，直接读即可）：")
        sb.appendLine("· 概要：${strOf(e["semantic_summary"]) ?: "（缺）"}")
        val steps = e["steps"] as? JsonArray
        val codes = steps?.mapNotNull { strOf((it as? JsonObject)?.get("action")) } ?: emptyList()
        sb.appendLine("· 动作顺序：${codes.joinToString("，")}")
        val says = sayLines(steps)
        if (says.isEmpty()) {
            sb.appendLine("· 台词：没有（整件事里没有人说话）")
        } else {
            sb.appendLine("· 台词（共 ${says.size} 句）：")
            says.forEachIndexed { i, s -> sb.appendLine("  ${i + 1}. $s") }
        }
        sb.append("请据此完成两个字段的输出。")
        return sb.toString()
    }

    // 抽出台词行（digest 与一致性检测共用）。
    private fun sayLines(steps: JsonElement?): List<String> {
        val arr = steps as? JsonArray ?: return emptyList()
        val out = mutableListOf<String>()
        arr.forEach { st ->
            val o = st as? JsonObject ?: return@forEach
            if (strOf(o["action"]) != "say") return@forEach
            val params = o["params"] as? JsonObject
            val text = strOf(params?.get("text")).orEmpty()
            if (text.isBlank()) return@forEach
            val who = when (strOf(params?.get("speaker"))) {
                "actor" -> "发起方"
                "target" -> "另一方"
                else -> "（未注明谁说）"
            }
            out += "$who 说：$text"
        }
        return out
    }

    // 程序可检测的不一致：有台词却填 null / 没台词却填了值。返回描述；null 表示一致。
    private fun inconsistency(p2: JsonObject, says: List<String>): String? {
        val ax = p2["comparison_axes"] as? JsonObject ?: return null
        val v = ax["verbal_directness"] ?: return null
        val isNull = v is JsonNull
        return when {
            says.isNotEmpty() && isNull ->
                "这条事件里有人说了话（「台词」块列了 ${says.size} 句），但你却把 verbal_directness 填成了 null"
            says.isEmpty() && !isNull ->
                "这条事件的「台词」块写着「没有」（整件事没有人说话），但你却给 verbal_directness 填了值"
            else -> null
        }
    }

    // 反喂反问：把事件整理稿 + 它自己的回答 + 不一致点（若有）喂回去，明确"这段回答是你本人写的"，请它反思重答。
    private fun buildReflectionMessage(
        digest: String,
        prevAnswer: String,
        says: List<String>,
        problem: String?,
    ): String = buildString {
        appendLine("你（同一个打标助手）刚才处理过下面这条事件，并给出了一个回答。")
        appendLine("注意：下面那段 JSON 是**你自己**（你本人）写的回答，不是别人填的——请把它当作你自己的答案来检查、反思。")
        appendLine()
        append(digest)
        appendLine()
        appendLine("【你自己的回答（你刚才写的）】：")
        appendLine(prevAnswer)
        appendLine()
        if (problem != null) {
            append("请反思你自己的这个回答：$problem。")
            if (says.isNotEmpty()) append("事件里的台词是：" + says.joinToString("；") + "。")
        } else {
            append("请复查你自己的这个回答：对照事件（概要、动作顺序、台词）逐项核对两个字段是否合适——尤其 verbal_directness 与「台词」块是否相配、档位与事件的语气是否相符。")
        }
        append("复查后请重新输出完整的 comparison_axes 和 suitable_time（只输出 JSON）。即使复查后你认为原回答正确，也要再完整输出一次。")
    }

    private fun verbalCell(p: JsonObject?): String =
        cellOf((p?.get("comparison_axes") as? JsonObject)?.get("verbal_directness"))

    // A′ 的 schema：把"「台词」块有没有句子"这一程序已确定的事实固化成结构约束——
    // 有台词 → verbal_directness 只能是 1-4（null 在约束解码下不可输出）；无台词 → 只能是 null。
    private fun gen31A2Schema(says: List<String>): String {
        val verbal = if (says.isNotEmpty()) """{"type":"integer","enum":[1,2,3,4]}"""
        else """{"type":"null"}"""
        return """{"type":"object","additionalProperties":false,"properties":{"comparison_axes":{"type":"object","additionalProperties":false,"properties":{"verbal_directness":$verbal,"emotion_visibility":{"type":"integer","enum":[1,2,3,4]}},"required":["verbal_directness","emotion_visibility"]},"suitable_time":{"type":"array","items":{"type":"string","enum":["MORNING","AFTERNOON","EVENING","NIGHT"]}}},"required":["comparison_axes","suitable_time"]}"""
    }

    // 值级签名：两个轴 + suitable_time，用于"复查是否真的改了值"。
    private fun answerSig(p: JsonObject?): String? {
        if (p == null) return null
        val ax = p["comparison_axes"] as? JsonObject
        return "${cellOf(ax?.get("verbal_directness"))}|${cellOf(ax?.get("emotion_visibility"))}|${p["suitable_time"]}"
    }

    private fun strOf(el: JsonElement?): String? =
        (el as? JsonPrimitive)?.takeIf { it != JsonNull }?.content

    private fun cellOf(el: JsonElement?): String = when {
        el == null -> "missing"
        el is JsonNull -> "null"
        else -> el.toString()
    }

    private suspend fun runJudge(times: Int) {
        loadIfNeeded()
        var ok = 0
        var hit = 0
        var msSum = 0L
        repeat(times) { i ->
            val sample = JUDGE_SAMPLES[i % JUDGE_SAMPLES.size]
            val user = JUDGE_RULES + "\n## 待判定事件\n" + sample.first + "\n请判定该事件是否 OOC，输出严格的 JSON。"
            try {
                val r = completeOnce(
                    system = JUDGE_SYSTEM,
                    user = user,
                    schema = JUDGE_SCHEMA,
                    temperature = 0.1,
                    maxOut = 320,
                    constrained = true,
                    seed = 2000 + i,
                )
                val parsed = LlmJson.parse(r.text)
                val okNow = parsed != null
                var got: Boolean? = null
                if (parsed != null) {
                    got = parsed["consistent"]?.toString()?.toBooleanStrictOrNull()
                    if (got != null && got == sample.second) hit++
                    if (okNow) ok++
                }
                msSum += r.ms
                log(
                    "RUN task=judge i=${i + 1}/$times ms=${r.ms} chars=${r.text.length} " +
                        "jsonOk=$okNow consistent=$got expect=${sample.second} pssMb=${pssMb()}"
                )
                if (!okNow) log("JUDGE TEXT #${i + 1}: ${r.text}")
            } catch (t: Throwable) {
                log("RUN task=judge i=${i + 1}/$times ERROR ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        log("SUMMARY task=judge jsonOk=$ok/$times acc=$hit/$times avgMs=${if (times > 0) msSum / times else 0}")
        logMem("afterJudge")
    }

    // ---------- 工具 ----------

    private fun pssMb(): Int {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val infos = am.getProcessMemoryInfo(intArrayOf(Process.myPid()))
        return (infos.firstOrNull()?.totalPss ?: 0) / 1024
    }

    private fun logMem(tag: String) {
        log("MEM tag=$tag processPssMb=${pssMb()}")
    }

    private fun log(line: String) {
        Log.i(TAG, "[spike] $line")
        runOnUiThread {
            logView.append(line + "\n")
            (logView.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    // ---------- 提示词（代表性样本；节点 04/05/06 落地前的量测基线） ----------

    private companion object {
        const val TAG = "PS_SPIKE"

        val GEN_SYSTEM = """
            你是角色互动事件意图生成器。根据角色设定、当前时间与灵感种子，生成一个互动事件的高层意图。
            要求：
            1. semantic_summary 用一句 10-20 字的话描述事件核心意图：只写谁对谁做了什么（A 或 B 都可以是主动方），不写具体动作、物品、场景细节。
            2. comparison_axes 七个维度必须全部填写，取值 0-100。
            3. action_family 用逗号分隔的行为代码序列（如 walk_to,hand_item,verbal_check）。
            4. suggested_time_window 填 morning/afternoon/evening/night，应与当前时间一致。
            5. 输出必须是严格的 JSON 格式，不要包含任何其他文本。
        """.trimIndent()

        // 约束解码（ResponseFormat.json）要点：加 additionalProperties:false，
        // 语法才会在下完必填字段后强制收尾；否则模型可继续追加任意键并退化成循环（v1 实测 0/4）
        val GEN_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{"semantic_summary":{"type":"string"},"comparison_axes":{"type":"object","additionalProperties":false,"properties":{"scene_publicness":{"type":"integer"},"physical_intimacy":{"type":"integer"},"emotion_visibility":{"type":"integer"},"verbal_directness":{"type":"integer"},"relationship_push":{"type":"integer"},"care_intensity":{"type":"integer"},"novelty_level":{"type":"integer"}},"required":["scene_publicness","physical_intimacy","emotion_visibility","verbal_directness","relationship_push","care_intensity","novelty_level"]},"action_family":{"type":"string"},"suggested_time_window":{"type":"string"}},"required":["semantic_summary","comparison_axes","action_family","suggested_time_window"]}
        """.trimIndent()

        val GEN_USER = """
            ## 当前时间
            现在是深夜（night）。
            ## 角色设定
            角色 A：赫拉克勒斯——性格直率，体力劳动者，不善言辞但心思细腻。
            角色 B：普罗米修斯——学者，双腿行动不便，长期伏案工作。
            ## 灵感种子
            深夜赶稿，一方想劝另一方休息。
            请根据以上信息生成一个互动事件的高层意图，输出严格的 JSON。
        """.trimIndent()

        // ---------- v3（2026-10-10）：生成提示词锚点实验 ----------
        // 问题：AI 指认 comparison_axes 时是否"明白字段含义"（定义 + 四档锚点 vs 无锚对照）。
        // 锚点方向约定：强度向（1 弱 → 4 强），与判定侧「容忍档 = 5 − 角色档」配套（06 回写后定稿）。
        // enum 探针：LiteRT-LM 约束解码对 JSON Schema enum 的支持（06 §7 待验证项）。

        val GEN3_ANCHOR_SYSTEM = """
            你是角色互动事件意图生成器。根据角色设定、当前时间与灵感种子，生成一个互动事件的高层意图。
            要求：
            1. semantic_summary 用一句 10-20 字的话描述事件核心意图：只写谁对谁做了什么（A 或 B 都可以是主动方），不写具体动作、物品、场景细节。
            2. comparison_axes 只填两维，各在 1-4 档里选一个整数；这两维描述的是「这件事本身是什么样」的客观属性，不是「越界程度」：
               - verbal_directness —— 这件事里话说得有多直白。完全没有说话时填 null。
                 1 很含蓄（绕着说、只说一半）
                 2 偏含蓄（话留白，要对方领会）
                 3 偏直接（基本直说，少有修饰）
                 4 很直白（想什么说什么）
               - emotion_visibility —— 这件事里情绪有多看得见。
                 1 看不出（情绪压着）
                 2 偏内敛（只露一点）
                 3 偏外露（能明显看出来）
                 4 全写在脸上（藏不住）
            3. action_family 用逗号分隔的行为代码序列（如 walk_to,hand_item,verbal_check）。
            4. suggested_time_window 填 morning/afternoon/evening/night，应与当前时间一致。
            5. 输出必须是严格的 JSON 格式，不要包含任何其他文本。
        """.trimIndent()

        // 无锚对照：同 schema、同两维，只去掉定义与档位说明（A/B 单变量 = 解释这一块）。
        val GEN3_PLAIN_SYSTEM = """
            你是角色互动事件意图生成器。根据角色设定、当前时间与灵感种子，生成一个互动事件的高层意图。
            要求：
            1. semantic_summary 用一句 10-20 字的话描述事件核心意图：只写谁对谁做了什么（A 或 B 都可以是主动方），不写具体动作、物品、场景细节。
            2. comparison_axes 只填两维（verbal_directness / emotion_visibility），各取值 1-4；完全没有说话时 verbal_directness 填 null。
            3. action_family 用逗号分隔的行为代码序列（如 walk_to,hand_item,verbal_check）。
            4. suggested_time_window 填 morning/afternoon/evening/night，应与当前时间一致。
            5. 输出必须是严格的 JSON 格式，不要包含任何其他文本。
        """.trimIndent()

        // v3 schema：两维；verbal 可 null（完全无语言）。仍带 additionalProperties:false（v2 结论）。
        val GEN3_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{"semantic_summary":{"type":"string"},"comparison_axes":{"type":"object","additionalProperties":false,"properties":{"verbal_directness":{"type":["integer","null"]},"emotion_visibility":{"type":"integer"}},"required":["verbal_directness","emotion_visibility"]},"action_family":{"type":"string"},"suggested_time_window":{"type":"string"}},"required":["semantic_summary","comparison_axes","action_family","suggested_time_window"]}
        """.trimIndent()

        // enum 探针：emotion_visibility 加 enum 钉 {1,2,3,4}（与 GEN3_SCHEMA 只差这一个变量）。
        val GEN3_SCHEMA_ENUM = """
            {"type":"object","additionalProperties":false,"properties":{"semantic_summary":{"type":"string"},"comparison_axes":{"type":"object","additionalProperties":false,"properties":{"verbal_directness":{"type":["integer","null"]},"emotion_visibility":{"type":"integer","enum":[1,2,3,4]}},"required":["verbal_directness","emotion_visibility"]},"action_family":{"type":"string"},"suggested_time_window":{"type":"string"}},"required":["semantic_summary","comparison_axes","action_family","suggested_time_window"]}
        """.trimIndent()

        // v3 样本 3 条（跨档）：① = 原样本（有语言，中档）② 无语言（verbal 应 null、情感低档）③ 高直白高外露（应高档）。
        // 10 次跑里轮换（i % size）；「应」只作事后判合理性的参照，不参与程序判定。
        val GEN3_SAMPLES = listOf(
            """
                ## 当前时间
                现在是深夜（night）。
                ## 角色设定
                角色 A：赫拉克勒斯——性格直率，体力劳动者，不善言辞但心思细腻。
                角色 B：普罗米修斯——学者，双腿行动不便，长期伏案工作。
                ## 灵感种子
                深夜赶稿，一方想劝另一方休息。
                请根据以上信息生成一个互动事件的高层意图，输出严格的 JSON。
            """.trimIndent(),
            """
                ## 当前时间
                现在是夜里（night）。
                ## 角色设定
                角色 A：赫拉克勒斯——性格直率，体力劳动者，不善言辞但心思细腻。
                角色 B：普罗米修斯——学者，双腿行动不便，长期伏案工作。
                ## 灵感种子
                普罗米修斯靠在轮椅里睡着了，赫拉克勒斯轻手轻脚给他披上外衣，又把散落的稿纸码整齐，始终没有说话。
                请根据以上信息生成一个互动事件的高层意图，输出严格的 JSON。
            """.trimIndent(),
            """
                ## 当前时间
                现在是傍晚（evening）。
                ## 角色设定
                角色 A：赫拉克勒斯——性格直率，体力劳动者，不善言辞但心思细腻。
                角色 B：普罗米修斯——学者，双腿行动不便，长期伏案工作。
                ## 灵感种子
                赫拉克勒斯发现普罗米修斯又是一整天没吃东西，把饭碗放到他面前，大声说：先吃饭，稿子不会跑。
                请根据以上信息生成一个互动事件的高层意图，输出严格的 JSON。
            """.trimIndent(),
        )

        // ---------- v3.1（2026-10-10）生成形态：五字段三层说明 + steps/「说」落点；单段 vs 生成+打分两段 ----------

        // 【输入块】spike fixture：模拟生产上下文（动作库=闭集参考；说话样本=风格参考）。
        // 注：动作码为 fixture 子集，随 04/05 码表落库后同步替换为全表投影。
        val GEN31_CONTEXT = """
            ## 动作库（只能从这里挑动作码，不能自己编）
            stand(站着) walk(走到别处) sit(坐下) lie_down(躺下) pick_up(拿起东西) put_down(放下东西) hand_item(把东西递给对方)
            wave(挥手) push_pull(推或拉) eat(吃东西) drink(喝) say(说出一句话) turn(转向对方)
            hold_hands(牵手) hug(拥抱) lean(靠在对方身上) hold(抱起对方)
            ## 说话样本（只说“这类角色会怎么说话”，不要照抄）
            赫拉克勒斯：先把饭吃了，别的等会儿说。／我不太会讲，但这事得办。
            普罗米修斯：再给我十分钟，这一节就完了。／……劳烦你了。
        """.trimIndent()

        val GEN31_SYSTEM = """
            你是角色互动事件生成器。根据【输入】里的角色设定、当前时间、动作库、说话样本和灵感种子，生成一条新的互动事件。
            输出包含下面 5 个部分，按此顺序填写。每个部分先讲清：它是什么（定义）、系统拿它做什么（用途）、你依据什么填（依据）。全部读完再开始写。

            1. semantic_summary（概要）
               定义：这条事件的一句话概要。
               用途：显示在清单和历史页；系统也拿它和近期事件比对，判断是不是“差不多的事”（查重）。
               依据：从灵感种子出发（要能看出种子的影子）；只写“谁对谁做了什么”（谁主动都可以）；10-20 字。
               注意：写你自己根据种子造出的这件事，不要把灵感种子原句抄一遍；不写具体动作、物品、场景细节——系统靠它比对“像不像”，细节写多了，同一件事换个说法就对不上。
               示例：“赫拉克勒斯劝普罗米修斯先吃饭。”

            2. steps（步骤序列）
               定义：角色按顺序依次做的一串动作；事件发生后就按这个顺序做。
               依据：动作只能从【动作库】里挑（只能挑、不能编）；连贯一体的动作（如先做饭再吃饭）合成同一条事件的多步骤，按发生顺序排；做什么、怎么排要合角色性格、关系和种子情境。
               注意：至少写 1 步（不能输出空数组）；每一步只写一个动作码；「说」也是其中一个动作，台词挂在它上面（见第 3 条）；除「说」外的步骤，params 可以整个省略——不要给别的步骤填 speaker 或 text。

            3. 「说」步骤——台词写在这里（对 steps 里的「说」补充说明）
               定义：角色说出一句话。播到这一步，角色头顶弹对话框显示这句话。
               依据：有话说才写「说」步骤（一条事件 0-2 句；整件事可以不说话——不说话就一个「说」步骤都不写）；说什么参考【说话样本】贴近该角色的风格，但不要照抄。
               写法：一个带台词的「说」步骤长这样——{"action":"say","params":{"speaker":"actor","text":"先吃饭，稿子不会跑。"}}
               —— 台词写进 params 的 text 字段；speaker 说明这句话是谁说的（actor = 发起方，target = 另一方）；单句不超过 30 字；text 里只写说出来的话本身——不带引号、不写旁白、不写动作描述、不写“某某说：”这样的前缀。

            4. comparison_axes（两个客观档位）
               定义：只描述“这件事本身是什么样”的两把尺子，各在 1-4 档里选一个整数。
               用途：它们是事件的客观属性，系统会拿它和每个角色各自的脾气分开比较，谁接不接受由系统判。所以你的任务只是如实描述这件事：不是判断合不合适，不是判断越不越界。
               - verbal_directness（话有多直白）——先写完 steps，再回头看里面的「说」步骤：
                   ① 一个「说」步骤都没有 → 填 null（这件事不涉及说话）；
                   ② 有 → 看这些话的说法：绕着说、只说一半 = 1；话留白、要对方领会 = 2；基本直说、少有修饰 = 3；想什么说什么 = 4。
                   一句含蓄一句直白时，以最直白的那句为准。
               - emotion_visibility（情绪有多看得见）：
                   定义：这件事做出来、话说出来之后，对方能看出多少情绪——不是角色心里多激动，是“表现出多少”。
                   填法：回头看 steps 整体给人的感觉：1 看不出（情绪压着）；2 偏内敛（只露一点）；3 偏外露（能明显看出）；4 全写在脸上（藏不住）。

            5. suitable_time（适合发生的时段）
               定义：这件事适合在哪些时段发生。
               用途：系统只在适合的时段安排它发生；弹给用户确认时“摘时段”也用这里。
               依据：从事件本身的性质想（比如劝吃饭适合饭点）。只能填 MORNING / AFTERNOON / EVENING / NIGHT，可以多个；拿不准或什么时段都行 → 空数组 []（表示不限）。

            输出：严格按 semantic_summary → steps → comparison_axes → suitable_time 的顺序生成 JSON，不要任何解释文字。
        """.trimIndent()

        // V2 的 A1：生成段（概要 / 步骤 / 台词）。
        val GEN31_A1_SYSTEM = """
            你是角色互动事件生成器。根据【输入】里的角色设定、当前时间、动作库、说话样本和灵感种子，生成一条新的互动事件。
            输出包含下面 3 个部分，按此顺序填写。每个部分先讲清：它是什么（定义）、系统拿它做什么（用途）、你依据什么填（依据）。全部读完再开始写。

            1. semantic_summary（概要）
               定义：这条事件的一句话概要。
               用途：显示在清单和历史页；系统也拿它和近期事件比对，判断是不是“差不多的事”（查重）。
               依据：从灵感种子出发（要能看出种子的影子）；只写“谁对谁做了什么”（谁主动都可以）；10-20 字。
               注意：写你自己根据种子造出的这件事，不要把灵感种子原句抄一遍；不写具体动作、物品、场景细节——系统靠它比对“像不像”，细节写多了，同一件事换个说法就对不上。
               示例：“赫拉克勒斯劝普罗米修斯先吃饭。”

            2. steps（步骤序列）
               定义：角色按顺序依次做的一串动作；事件发生后就按这个顺序做。
               依据：动作只能从【动作库】里挑（只能挑、不能编）；连贯一体的动作（如先做饭再吃饭）合成同一条事件的多步骤，按发生顺序排；做什么、怎么排要合角色性格、关系和种子情境。
               注意：至少写 1 步（不能输出空数组）；每一步只写一个动作码；「说」也是其中一个动作，台词挂在它上面（见第 3 条）；除「说」外的步骤，params 可以整个省略——不要给别的步骤填 speaker 或 text。

            3. 「说」步骤——台词写在这里（对 steps 里的「说」补充说明）
               定义：角色说出一句话。播到这一步，角色头顶弹对话框显示这句话。
               依据：有话说才写「说」步骤（一条事件 0-2 句；整件事可以不说话——不说话就一个「说」步骤都不写）；说什么参考【说话样本】贴近该角色的风格，但不要照抄。
               写法：一个带台词的「说」步骤长这样——{"action":"say","params":{"speaker":"actor","text":"先吃饭，稿子不会跑。"}}
               —— 台词写进 params 的 text 字段；speaker 说明这句话是谁说的（actor = 发起方，target = 另一方）；单句不超过 30 字；text 里只写说出来的话本身——不带引号、不写旁白、不写动作描述、不写“某某说：”这样的前缀。

            输出：严格按 semantic_summary → steps 的顺序生成 JSON，不要任何解释文字。
        """.trimIndent()

        // V2 的 A2：打分段（读 A1 产物整理出的平铺文字：概要 / 动作顺序 / 台词 → 两维档位 + 适合时段）。
        val GEN31_A2_SYSTEM = """
            请阅读用户消息里给出的一条已经生成好的事件（已整理成文字：概要 / 动作顺序 / 台词），完成两件事：
            1. 给这项事件填两个客观档位（comparison_axes）；
            2. 判断它适合在哪些时段发生（suitable_time）。
            两部分各自的定义、用途、填写依据如下——特别注意：填档位时要看「台词」那几行，依据你眼前实际写着的内容来填。

            comparison_axes（两个客观档位）
            定义：只描述“这件事本身是什么样”的两把尺子，各在 1-4 档里选一个整数。
            用途：它们是事件的客观属性，系统会拿它和每个角色各自的脾气分开比较，谁接不接受由系统判。所以你的任务只是如实描述这件事：不是判断合不合适，不是判断越不越界。
            - verbal_directness（话有多直白）——只看「台词」那几行：
                ① 台词写着「没有」→ 填 null（这件事不涉及说话）；
                ② 有 → 看这些话的说法：绕着说、只说一半 = 1；话留白、要对方领会 = 2；基本直说、少有修饰 = 3；想什么说什么 = 4。
                一句含蓄一句直白时，以最直白的那句为准。
            - emotion_visibility（情绪有多看得见）：
                定义：这件事做出来、话说出来之后，对方能看出多少情绪——不是角色心里多激动，是“表现出多少”。
                填法：看动作顺序和台词整体给人的感觉：1 看不出（情绪压着）；2 偏内敛（只露一点）；3 偏外露（能明显看出）；4 全写在脸上（藏不住）。

            suitable_time（适合发生的时段）
            定义：这件事适合在哪些时段发生。
            用途：系统只在适合的时段安排它发生；弹给用户确认时“摘时段”也用这里。
            依据：从事件本身的性质想（比如劝吃饭适合饭点）。只能填 MORNING / AFTERNOON / EVENING / NIGHT，可以多个；拿不准或什么时段都行 → 空数组 []（表示不限）。

            输出：严格按 comparison_axes → suitable_time 的顺序生成 JSON，不要任何解释文字。
        """.trimIndent()

        // 追问轮用：不约束输出的独立会话（同会话追问会被上一个 JSON 格式锁住，复读原 JSON）。
        val GEN31_A2_WHY_SYSTEM = """
            你刚完成一次事件打标。用户消息里再次给出你当时看到的事件、你当时的回答、以及一个追问。
            直接回答追问，只输出理由本身：一两句话，不要输出 JSON，不要重复事件内容。
        """.trimIndent()

        // v3.1 输出 schema：字段顺序=生成顺序（先写 steps/台词，再打档位——打标对象已存在）。
        val GEN31_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{"semantic_summary":{"type":"string"},"steps":{"type":"array","items":{"type":"object","additionalProperties":false,"properties":{"action":{"type":"string","enum":["stand","walk","sit","lie_down","pick_up","put_down","hand_item","wave","push_pull","eat","drink","say","turn","hold_hands","hug","lean","hold"]},"params":{"type":"object","additionalProperties":false,"properties":{"speaker":{"type":"string","enum":["actor","target"]},"text":{"type":"string"}}}},"required":["action"]}},"comparison_axes":{"type":"object","additionalProperties":false,"properties":{"verbal_directness":{"type":["integer","null"]},"emotion_visibility":{"type":"integer","enum":[1,2,3,4]}},"required":["verbal_directness","emotion_visibility"]},"suitable_time":{"type":"array","items":{"type":"string","enum":["MORNING","AFTERNOON","EVENING","NIGHT"]}}},"required":["semantic_summary","steps","comparison_axes","suitable_time"]}
        """.trimIndent()

        val GEN31_A1_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{"semantic_summary":{"type":"string"},"steps":{"type":"array","items":{"type":"object","additionalProperties":false,"properties":{"action":{"type":"string","enum":["stand","walk","sit","lie_down","pick_up","put_down","hand_item","wave","push_pull","eat","drink","say","turn","hold_hands","hug","lean","hold"]},"params":{"type":"object","additionalProperties":false,"properties":{"speaker":{"type":"string","enum":["actor","target"]},"text":{"type":"string"}}}},"required":["action"]}}},"required":["semantic_summary","steps"]}
        """.trimIndent()

        val GEN31_A2_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{"comparison_axes":{"type":"object","additionalProperties":false,"properties":{"verbal_directness":{"type":["integer","null"]},"emotion_visibility":{"type":"integer","enum":[1,2,3,4]}},"required":["verbal_directness","emotion_visibility"]},"suitable_time":{"type":"array","items":{"type":"string","enum":["MORNING","AFTERNOON","EVENING","NIGHT"]}}},"required":["comparison_axes","suitable_time"]}
        """.trimIndent()

        // 反思回路总开关：A′ 轮=false（只测 schema 固化）；历史轮=true。
        const val GEN31_REFLECT_ENABLED = false

        // v3.1 样本 = GEN3 三条种子（跨档：①中档 ②无语言 ③高直白）+ 输入块（动作库/说话样本）。
        val GEN31_SAMPLES = GEN3_SAMPLES.map { it.replace("## 灵感种子", GEN31_CONTEXT + "\n## 灵感种子") }

        val JUDGE_SYSTEM = """
            你是角色一致性判定器。给你角色硬规则与一个事件描述，判断该事件是否与角色设定冲突（OOC）。
            要求：
            1. 只要事件与任一硬规则冲突，即判 consistent=false，并在 violation 中引用被违反的规则。
            2. 若事件与所有硬规则相容，consistent=true，violation 留空字符串。
            3. 输出必须是严格的 JSON 格式，不要包含任何其他文本。
        """.trimIndent()

        val JUDGE_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{"consistent":{"type":"boolean"},"violation":{"type":"string"},"reason":{"type":"string"}},"required":["consistent","violation","reason"]}
        """.trimIndent()

        val JUDGE_RULES = """
            ## 角色硬规则
            1. 普罗米修斯双腿行动不便，不能跑、跳、蹲、上下楼梯；出行依赖轮椅。
            2. 赫拉克勒斯不会读写长文，看不懂复杂的学术文本。
            3. 两人不会在他人面前进行亲密身体接触。
        """.trimIndent()

        /** 10 组对抗样本：text to 期望 consistent（true=不 OOC）。 */
        val JUDGE_SAMPLES = listOf(
            "赫拉克勒斯把温水递到普罗米修斯手边，顺手把散落的稿纸码整齐。" to true,
            "普罗米修斯快步跑上楼梯，去追已经出门的赫拉克勒斯。" to false,
            "赫拉克勒斯凑近看了眼稿子上的公式，然后和普罗米修斯讨论起推导过程。" to false,
            "在集市人多处，两人停下脚步，赫拉克勒斯俯身亲吻了普罗米修斯的脸颊。" to false,
            "深夜，普罗米修斯伏案写字，赫拉克勒斯把一条薄毯盖在他肩上。" to true,
            "普罗米修斯推着轮椅到窗边，示意赫拉克勒斯一起看外面下雨。" to true,
            "赫拉克勒斯一口气跑完三层楼梯，把药送到了普罗米修斯房间。" to true,
            "普罗米修斯蹲下来，仔细检查书架最下层的旧手稿。" to false,
            "赫拉克勒斯靠在轮椅旁，小声把今天街上的见闻讲给普罗米修斯听。" to true,
            "客厅里只有他们两人时，普罗米修斯轻轻握住赫拉克勒斯的手。" to true,
        )
    }
}
