package com.pairseedkmp.data.judgment

import com.pairseedkmp.data.Character_hard_rules
import com.pairseedkmp.domain.event.parseJsonStringList
import com.pairseedkmp.domain.judgment.HardRuleScope
import com.pairseedkmp.domain.judgment.JudgeHardRule

// 判定快照（06）读侧的行 → 值类型映射：硬规则表。

/**
 * 硬规则行 → 判定用规则（forbidden_codes JSON 数组解析；坏 / 空 / null → 空集 = 无投影，只走 AI 通道）。
 * [description] 原文直传：AI 冷读通道靠它（06 步骤 4）。
 */
internal fun Character_hard_rules.toJudgeHardRule(): JudgeHardRule = JudgeHardRule(
    characterId = character_id,
    ruleKey = rule_key,
    scope = HardRuleScope.fromCode(scope),
    forbiddenCodes = parseJsonStringList(forbidden_codes).toSet(),
    description = description,
)
