package com.diet.skills;

import java.util.List;
import java.util.Map;

/**
 * 版本化技能清单（M5 #50）。
 * <p>
 * 固定字段：name/version/description/input_schema/output_schema/allowed_tools/risk_level。
 * manifest 只描述能力与调用边界，不包含 Prompt、密钥或自主工具编排。
 */
public record SkillManifest(
        String name,
        String version,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        List<String> allowedTools,
        String riskLevel
) {

    /** 稳定资源 URI（resources/list 与 resources/read 使用，name 唯一性由 Registry 校验保证）。 */
    public String resourceUri() {
        return "skill://" + name;
    }
}
