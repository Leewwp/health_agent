package com.diet.health.session;

/**
 * 会话级类型化资源引用（43 号票）：替换旧数字 ID 列表。
 * <p>
 * type 为 MEAL/EXERCISE/ROUTINE；type 为 null 表示旧版数字列表遗留（无类型信息），
 * 按 MEAL+EXERCISE 双类型兼容排除（与旧行为一致，宁可多排除也不漏排除）。
 * ROUTINE 类型只保留在历史中，不参与 ADJUST 排除。
 */
public record SessionResourceRef(String type, String id) {

    /** 旧版数字列表遗留引用：无类型信息，按双类型兼容处理。 */
    public static SessionResourceRef legacy(String id) {
        return new SessionResourceRef(null, id);
    }
}
