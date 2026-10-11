package com.pairseedkmp.domain.seed

/**
 * 采集工具端口（《工作链路·种子池生长链路》1.1）：程序侧取语料 → AI 从中挑词；
 * 起步形态不做模型 function calling（端侧小模型不可靠），工具轮换由生长回环做。
 * 真实实现（新闻 RSS / 搜索摘要…）在数据层；取不到素材 / 网络失败 → 抛异常，由回环换下一个工具。
 */
interface SeedMaterialSource {
    /** 工具标签（日志 / 素材来源提示用）。 */
    val label: String

    /** 取一批生活化语料（一行为一条：新闻标题 / 搜索摘要…）；过滤空白行由回环做。 */
    suspend fun fetch(): List<String>
}
