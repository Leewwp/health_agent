package com.diet.health.eval;

/** 嵌入文本来源（#77 消融）：用户原话 vs 槽位拼接。 */
public enum EmbeddingTextMode {
    /** 用户原话：直接使用查询文本做嵌入。 */
    USER_TEXT,
    /** 槽位拼接：嵌入文本置空，由检索器回退到槽位值排序拼接。 */
    SLOT_CONCAT
}
