package com.diet.mcp.tool;

import com.diet.health.enums.ActivityLevel;
import com.diet.health.enums.ProfileGoal;
import com.diet.health.enums.ProfileSex;
import com.diet.health.profile.EnergyCalculator;
import com.diet.health.profile.HealthProfileService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具 calculate_targets（M5 #47）：确定性计算每日能量区间。
 * <p>
 * 纯计算、无持久化：复用 24 号契约的 Mifflin-St Jeor 公式与档案边界校验，
 * 生理性别缺失时取男/女公式并集形成更宽区间；返回估算标记与计算依据。
 */
@Component
public class CalculateTargetsTool implements McpToolSpec {

    static final String NAME = "calculate_targets";

    public CalculateTargetsTool() {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public McpServerFeatures.SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("age", Map.of("type", "integer",
                "description", "年龄（" + HealthProfileService.MIN_AGE + "-" + HealthProfileService.MAX_AGE + "）"));
        properties.put("sex", Map.of("type", "string", "enum",
                List.of("MALE", "FEMALE"), "description", "生理性别，选填（缺失时取并集较宽区间）"));
        properties.put("heightCm", Map.of("type", "number", "description", "身高（厘米）"));
        properties.put("weightKg", Map.of("type", "number", "description", "体重（千克）"));
        properties.put("activityLevel", Map.of("type", "string", "enum",
                List.of("SEDENTARY", "LIGHT", "MODERATE"), "description", "活动水平"));
        properties.put("goal", Map.of("type", "string", "enum",
                List.of("MAINTAIN", "LOSE", "GAIN"), "description", "主要目标"));
        return McpToolSupport.tool(NAME,
                "按健康档案参数确定性计算每日能量区间（kcal，估算值，不写入档案）",
                properties, List.of("age", "heightCm", "weightKg", "activityLevel", "goal"),
                outputSchema(),
                this::handle);
    }

    /** 输出契约（#63）：能量区间 + 估算标记 + 计算依据封闭对象。 */
    private static Map<String, Object> outputSchema() {
        return McpToolSupport.objectType(Map.of(
                "lowKcal", McpToolSupport.numberType(),
                "highKcal", McpToolSupport.numberType(),
                "estimated", McpToolSupport.booleanType(),
                "calcBasis", McpToolSupport.stringType()),
                List.of("lowKcal", "highKcal", "estimated", "calcBasis"));
    }

    private McpSchema.CallToolResult handle(Map<String, Object> args) {
        int age = McpToolSupport.optionalInt(args, "age", -1);
        if (age < HealthProfileService.MIN_AGE || age > HealthProfileService.MAX_AGE) {
            throw McpToolSupport.invalidParams("年龄需在 " + HealthProfileService.MIN_AGE + "-"
                    + HealthProfileService.MAX_AGE + " 之间（仅面向成年人）");
        }
        ProfileSex sex = parseEnum(args, "sex", ProfileSex.class, true);
        double heightCm = optionalDouble(args, "heightCm", -1);
        double weightKg = optionalDouble(args, "weightKg", -1);
        if (heightCm < HealthProfileService.MIN_HEIGHT_CM || heightCm > HealthProfileService.MAX_HEIGHT_CM) {
            throw McpToolSupport.invalidParams("身高需在 " + (int) HealthProfileService.MIN_HEIGHT_CM + "-"
                    + (int) HealthProfileService.MAX_HEIGHT_CM + " 厘米之间");
        }
        if (weightKg < HealthProfileService.MIN_WEIGHT_KG || weightKg > HealthProfileService.MAX_WEIGHT_KG) {
            throw McpToolSupport.invalidParams("体重需在 " + (int) HealthProfileService.MIN_WEIGHT_KG + "-"
                    + (int) HealthProfileService.MAX_WEIGHT_KG + " 千克之间");
        }
        ActivityLevel activity = parseEnum(args, "activityLevel", ActivityLevel.class, false);
        ProfileGoal goal = parseEnum(args, "goal", ProfileGoal.class, false);

        EnergyCalculator.EnergyRange range = EnergyCalculator.dailyRange(
                age, sex, heightCm, weightKg, activity, goal);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lowKcal", range.lowKcal());
        result.put("highKcal", range.highKcal());
        result.put("estimated", true);
        result.put("calcBasis", HealthProfileService.buildCalcBasis(
                new HealthProfileService.HealthProfileInput(age, sex, heightCm, weightKg, activity, goal, null,
                        null, null)));
        return McpToolSupport.success(result,
                "每日能量区间：" + range.lowKcal() + "-" + range.highKcal() + " kcal（估算值）");
    }

    private double optionalDouble(Map<String, Object> args, String key, double defaultValue) {
        Object value = args.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw McpToolSupport.invalidParams("参数 " + key + " 必须是数字");
        }
        return number.doubleValue();
    }

    private <E extends Enum<E>> E parseEnum(Map<String, Object> args, String key, Class<E> type, boolean nullable) {
        Object value = args.get(key);
        if (value == null) {
            if (nullable) {
                return null;
            }
            throw McpToolSupport.invalidParams("参数 " + key + " 不能为空");
        }
        try {
            return Enum.valueOf(type, String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw McpToolSupport.invalidParams("参数 " + key + " 必须是 " + List.of(type.getEnumConstants()));
        }
    }
}
