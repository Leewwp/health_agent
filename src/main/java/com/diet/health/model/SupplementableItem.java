package com.diet.health.model;

import java.util.List;

/**
 * 计划简报“可补充项”（简报补充回路规格 v3.2 前后端契约）：
 * {key, label, examples, filled}；只列未填项（filled=false），前端渲染为可点 chip，
 * 点击后把“属性名：”参考输入填入输入框并聚焦。计划侧三个领域（MEAL/EXERCISE/COMPOSITE）通用；
 * 推荐预检沿用既有 optionalSlots 字段不变。
 */
public record SupplementableItem(String key, String label, List<String> examples, boolean filled) {
    public SupplementableItem {
        examples = examples == null ? List.of() : List.copyOf(examples);
    }
}
