package com.pairseedkmp.domain.judgment.samples

import com.pairseedkmp.domain.event.ComparisonAxes
import com.pairseedkmp.domain.event.EventCandidate
import com.pairseedkmp.domain.event.EventStep
import com.pairseedkmp.domain.event.GrowCharacter
import com.pairseedkmp.domain.judgment.AXIS_EMOTION_VISIBILITY
import com.pairseedkmp.domain.judgment.AXIS_PHYSICAL_INTIMACY
import com.pairseedkmp.domain.judgment.AXIS_VERBAL_DIRECTNESS
import com.pairseedkmp.domain.judgment.HardRuleScope
import com.pairseedkmp.domain.judgment.JudgeCharacter
import com.pairseedkmp.domain.judgment.JudgeHardRule
import com.pairseedkmp.domain.judgment.JudgmentSnapshot
import com.pairseedkmp.domain.judgment.SceneVisibility

/**
 * 判定样例集（06 步骤 6）：18 条，覆盖应通过 / 应确认 / 应拒 / 擦边对抗四组。
 *
 * 每条 = 固定候选 + 角色设定（自足快照：码表 / 硬规则 / 场景旗标全内嵌，不依赖库），
 * 期望标注与起步参数（权重 1.0 / 0.6 / 0.4、门槛 15 / 65）的离散裁决带逐档对应：
 * 0 / 13 过 · 20 / 27 / 33 / 40 / 60 拿不准 · 67 / 100 拒 · 硬规则码命中 / 缺料各有专条。
 * 覆盖的 R 值：0 · 13 · 20 · 27 · 33 · 40 · 60 · 67 · 100 + 缺料 ×3 + 直通拒 ×2。
 *
 * 用途：域名侧校准跑（`JudgeSampleCalibrationTest`）；07 面板「样例区块」一键装载。
 * 注意：改一处权重 / 代价 → 裁决带移动 → 期望标注须重审（06 计划 §4 标记模式校准）。
 * 注意：object 属性按声明顺序初始化——固定件 / 分组列表在前、[ALL] 聚合最后。
 */
object JudgeSampleSet {

    // —— 内嵌固定件（纯数据；与测试夹具同口径，刻意不复用 commonTest 源集） ——

    /** 17 码定档（2026-10-11；单人 13 码全 1 / 牵手·靠 3 / 拥抱·抱起 4）。 */
    private val SAMPLE_BEHAVIOR_LEVELS: Map<String, Int> = mapOf(
        "stand" to 1, "walk" to 1, "sit" to 1, "lie_down" to 1, "turn" to 1, "wave" to 1,
        "say" to 1, "eat" to 1, "drink" to 1, "pick_up" to 1, "put_down" to 1, "push_pull" to 1,
        "hand_item" to 1, "hold_hands" to 3, "lean" to 3, "hug" to 4, "hold" to 4,
    )

    // 样例集的两个通用夹具角色（名字 / 设定为样例专用，不指向库内数据）
    private val noPublicHoldHands = JudgeHardRule(
        characterId = 1, ruleKey = "no_public_intimacy", scope = HardRuleScope.PUBLIC,
        forbiddenCodes = setOf("hold_hands"), description = "不会在人前牵手。",
    )
    private val noStrongHold = JudgeHardRule(
        characterId = 1, ruleKey = "no_strong_hold", scope = HardRuleScope.ALWAYS,
        forbiddenCodes = setOf("hold"), description = "不接受被整个人抱起来。",
    )

    // —— 应通过（R = 0 / 13） ——

    private val PASS_SAMPLES = listOf(
        sample(
            id = "pass-01", group = SampleGroup.PASS, title = "安安静静坐在一起聊天",
            summary = "两个人在沙发上坐着，聊了聊今天的事。",
            expected = SampleVerdict.PASS, cause = SampleCause.RISK,
            note = "零偏离：日常低强度，三维都在容忍内（R=0）",
            actor = roleA(phys = 2, verb = 2, emo = 2), target = roleB(phys = 2, verb = 2, emo = 2),
            codes = listOf("sit", "say"), verbal = 1, emotion = 1,
        ),
        sample(
            id = "pass-02", group = SampleGroup.PASS, title = "笑意藏不住，情绪有点外露",
            summary = "看着对方，眼里的高兴藏都藏不住。",
            expected = SampleVerdict.PASS, cause = SampleCause.RISK,
            note = "情感 +1 档 = 13（双方情感软档 4 档档位保守也够容忍）——贴着放行带内沿（≤15）",
            actor = roleA(emo = 4), target = roleB(emo = 4),
            codes = listOf("turn", "sit"), emotion = 2,
        ),
        sample(
            id = "pass-03", group = SampleGroup.PASS, title = "独自收拾了下房间（单人事件）",
            summary = "一个人在房间里走了走，顺手把东西归了位。",
            expected = SampleVerdict.PASS, cause = SampleCause.RISK,
            note = "单人事件：容忍档 = 5 − 本人档；物理也在容忍内（R=0）",
            actor = roleA(phys = 2, verb = 2, emo = 2), target = null,
            codes = listOf("walk", "put_down"),
        ),
    )

    // —— 应确认（R = 20 / 27 / 33 / 40 / 60 与缺料 ×3） ——

    private val UNCERTAIN_SAMPLES = listOf(
        sample(
            id = "uncertain-01", group = SampleGroup.UNCERTAIN, title = "把烦心事讲得挺直白",
            summary = "认真讲了讲最近的烦心事，话说得很直接。",
            expected = SampleVerdict.UNCERTAIN, cause = SampleCause.RISK,
            note = "语言 +1 档 = 20——落两门槛之间",
            actor = roleA(verb = 3), target = roleB(verb = 3),
            codes = listOf("say"), verbal = 3,
        ),
        sample(
            id = "uncertain-02", group = SampleGroup.UNCERTAIN, title = "半夜情绪涌上来，心事摊开",
            summary = "半夜情绪一下涌上来，把心事一股脑说了出来。",
            expected = SampleVerdict.UNCERTAIN, cause = SampleCause.RISK,
            note = "情感 +2 档 = 27",
            actor = roleA(emo = 4), target = roleB(emo = 4),
            codes = listOf("sit"), emotion = 3,
        ),
        sample(
            id = "uncertain-03", group = SampleGroup.UNCERTAIN, title = "伸手抱了抱对方",
            summary = "走上去，伸手抱了抱对方。",
            expected = SampleVerdict.UNCERTAIN, cause = SampleCause.RISK,
            note = "物理 +1 档 = 33（拥抱级 4 档 + 双方接受度 2 档）",
            actor = roleA(phys = 2), target = roleB(phys = 2),
            codes = listOf("hug"),
        ),
        sample(
            id = "uncertain-04", group = SampleGroup.UNCERTAIN, title = "把喜欢说得很郑重",
            summary = "把压在心里的喜欢很郑重地说了出来。",
            expected = SampleVerdict.UNCERTAIN, cause = SampleCause.RISK,
            note = "语言 +2 档 = 40",
            actor = roleA(verb = 3), target = roleB(verb = 3),
            codes = listOf("say"), verbal = 4,
        ),
        sample(
            id = "uncertain-05", group = SampleGroup.UNCERTAIN, title = "几乎毫无保留地剖白",
            summary = "几乎毫无保留地剖白了自己。",
            expected = SampleVerdict.UNCERTAIN, cause = SampleCause.RISK,
            note = "语言 +3 档 = 60——贴着拦截门（<65）的最深拿不准",
            actor = roleA(verb = 4), target = roleB(verb = 4),
            codes = listOf("say"), verbal = 4,
        ),
        sample(
            id = "uncertain-06", group = SampleGroup.UNCERTAIN, title = "踩着凳子去够架子顶（未知动作码）",
            summary = "踩着凳子去够书架顶上的盒子。",
            expected = SampleVerdict.UNCERTAIN, cause = SampleCause.MISSING,
            note = "步骤码 climb 不在码表 → 物理档推不出 → fail-safe 强制待确认",
            actor = roleA(phys = 2, verb = 2, emo = 2), target = roleB(phys = 2, verb = 2, emo = 2),
            codes = listOf("climb", "pick_up"),
        ),
        sample(
            id = "uncertain-07", group = SampleGroup.UNCERTAIN, title = "紧紧抱了很久（一方软档未填）",
            summary = "紧紧抱了很久才松开。",
            expected = SampleVerdict.UNCERTAIN, cause = SampleCause.MISSING,
            note = "事件物理档 ≥ 2 而角色乙软档没填 → 容忍档算不出 → 待确认",
            actor = roleA(phys = 2), target = roleB(),
            codes = listOf("hug"),
        ),
        sample(
            id = "uncertain-08", group = SampleGroup.UNCERTAIN, title = "牵起对方的手（场景没标公开/私密）",
            summary = "在人前牵起了对方的手，又在人前松开。",
            expected = SampleVerdict.UNCERTAIN, cause = SampleCause.MISSING,
            note = "「不在人前牵手」按场景生效、码又命中候选，场景却没标公开/私密 → 命中与否判不出 → 待确认",
            actor = roleA(phys = 2, rules = listOf(noPublicHoldHands)), target = roleB(phys = 2),
            codes = listOf("hold_hands"), visibility = null,
        ),
    )

    // —— 应拒（R = 67 / 100 与硬规则码命中） ——

    private val REJECT_SAMPLES = listOf(
        sample(
            id = "reject-01", group = SampleGroup.REJECT, title = "一把把对方整个抱起来（撞硬规则码）",
            summary = "笑着上前，一把把对方整个人抱了起来。",
            expected = SampleVerdict.REJECT, cause = SampleCause.HARD_RULE,
            note = "硬规则码命中（hold）：直通拒、先于一切、不算风险分",
            actor = roleA(phys = 2, rules = listOf(noStrongHold)), target = roleB(phys = 2),
            codes = listOf("hold"),
        ),
        sample(
            id = "reject-02", group = SampleGroup.REJECT, title = "揽住对方，抱得很紧",
            summary = "揽住对方，抱得很紧。",
            expected = SampleVerdict.REJECT, cause = SampleCause.RISK,
            note = "物理 +2 档 = 67——越过拦截门槛（≥65）",
            actor = roleA(phys = 3), target = roleB(phys = 3),
            codes = listOf("hug"),
        ),
        sample(
            id = "reject-03", group = SampleGroup.REJECT, title = "全然不顾地抱紧（满偏离）",
            summary = "什么也顾不上，紧紧抱住了对方。",
            expected = SampleVerdict.REJECT, cause = SampleCause.RISK,
            note = "物理 +3 档 = 100——满偏离",
            actor = roleA(phys = 4), target = roleB(phys = 4),
            codes = listOf("hug"),
        ),
    )

    // —— 擦边对抗（贴着边界与设定，两侧成对） ——

    private val EDGE_SAMPLES = listOf(
        sample(
            id = "edge-01", group = SampleGroup.EDGE, title = "私下里牵住对方的手",
            summary = "四下无人，牵住了对方的手。",
            expected = SampleVerdict.PASS, cause = SampleCause.RISK,
            note = "与 edge-02 成对：公开类规则遇上私密场景 → 不算命中；物理也没超档 → 不误伤（R=0）",
            actor = roleA(phys = 2, rules = listOf(noPublicHoldHands)), target = roleB(phys = 2),
            codes = listOf("hold_hands"), visibility = SceneVisibility.PRIVATE,
        ),
        sample(
            id = "edge-02", group = SampleGroup.EDGE, title = "在人前牵住对方的手",
            summary = "人来人往的街上，牵住了对方的手。",
            expected = SampleVerdict.REJECT, cause = SampleCause.HARD_RULE,
            note = "与 edge-01 成对：同动作、只换场景旗标 → 命中直通拒",
            actor = roleA(phys = 2, rules = listOf(noPublicHoldHands)), target = roleB(phys = 2),
            codes = listOf("hold_hands"), visibility = SceneVisibility.PUBLIC,
        ),
        sample(
            id = "edge-03", group = SampleGroup.EDGE, title = "抱住对方（一方主动、一方保守）",
            summary = "上前把对方抱住了。",
            expected = SampleVerdict.UNCERTAIN, cause = SampleCause.RISK,
            note = "一方主动不算数：容忍档由最保守的一方定（5 − max 双方软档）→ 物理 +1 档 = 33 仍拿不准",
            actor = roleA(phys = 1), target = roleB(phys = 2),
            codes = listOf("hug"),
        ),
        sample(
            id = "edge-04", group = SampleGroup.EDGE, title = "情绪写在脸上，笑得很明显",
            summary = "笑得很明显，高兴全写在脸上。",
            expected = SampleVerdict.PASS, cause = SampleCause.RISK,
            note = "与 pass-02 同情感档（事件 2 档）：对方情感软档 3 档就够容忍 → 0 vs 13 两侧（软档定带）",
            actor = roleA(emo = 3), target = roleB(emo = 3),
            codes = listOf("turn"), emotion = 2,
        ),
    )

    /** 全部样例（按分组排列：应通过 → 应确认 → 应拒 → 擦边对抗）。 */
    val ALL: List<JudgeSample> = PASS_SAMPLES + UNCERTAIN_SAMPLES + REJECT_SAMPLES + EDGE_SAMPLES

    // —— 构造器 ——

    private fun roleA(
        phys: Int? = null,
        verb: Int? = null,
        emo: Int? = null,
        rules: List<JudgeHardRule> = emptyList(),
    ): JudgeCharacter = sampleChar(1, "角色甲", "样例角色甲：偏主动、表达直接。", phys, verb, emo, rules)

    private fun roleB(
        phys: Int? = null,
        verb: Int? = null,
        emo: Int? = null,
        rules: List<JudgeHardRule> = emptyList(),
    ): JudgeCharacter = sampleChar(2, "角色乙", "样例角色乙：偏保守、情绪内收。", phys, verb, emo, rules)

    private fun sampleChar(
        id: Long,
        name: String,
        summary: String,
        phys: Int?,
        verb: Int?,
        emo: Int?,
        rules: List<JudgeHardRule>,
    ): JudgeCharacter = JudgeCharacter(
        card = GrowCharacter(id = id, name = name, summary = summary, softAxesRaw = axesJson(phys, verb, emo)),
        hardRules = rules,
    )

    /** 只放有值的维（「不选 = 不进」）；全空 → null（该角色软档全缺）。 */
    private fun axesJson(phys: Int?, verb: Int?, emo: Int?): String? {
        val entries = listOfNotNull(
            phys?.let { AXIS_PHYSICAL_INTIMACY to it },
            verb?.let { AXIS_VERBAL_DIRECTNESS to it },
            emo?.let { AXIS_EMOTION_VISIBILITY to it },
        )
        if (entries.isEmpty()) return null
        return entries.joinToString(prefix = "{", postfix = "}") { (key, level) -> """"$key":{"level":$level}""" }
    }

    private fun sample(
        id: String,
        group: SampleGroup,
        title: String,
        summary: String,
        expected: SampleVerdict,
        cause: SampleCause?,
        note: String,
        actor: JudgeCharacter,
        target: JudgeCharacter? = null,
        visibility: SceneVisibility? = SceneVisibility.PRIVATE,
        codes: List<String>,
        verbal: Int? = null,
        emotion: Int = 1,
    ): JudgeSample {
        val candidate = EventCandidate(
            packId = 1,
            actorId = actor.id,
            targetId = target?.id,
            sceneId = 1,
            summary = summary,
            steps = codes.map { EventStep(it) },
            axes = ComparisonAxes(verbalDirectness = verbal, emotionVisibility = emotion),
            suitableTime = emptyList(),
        )
        return JudgeSample(
            id = id, group = group, title = title,
            expected = expected, expectedCause = cause, note = note,
            snapshot = JudgmentSnapshot(
                candidate = candidate,
                actor = actor,
                target = target,
                sceneVisibility = visibility,
                behaviorLevels = SAMPLE_BEHAVIOR_LEVELS,
            ),
        )
    }
}
