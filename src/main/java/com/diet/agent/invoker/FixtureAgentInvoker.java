package com.diet.agent.invoker;

import com.diet.agent.invoker.AgentInvoker.AgentInvocation;
import com.diet.agent.invoker.AgentInvoker.AgentInvocationResult;
import com.diet.health.risk.RiskRuleCatalog;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 固定夹具适配器：无 API key 时复现确定性主流程，用于自动化测试和离线演示。
 * <p>
 * 场景解析器根据 Prompt 文本关键词选择版本化固定响应；测试可通过构造器注入自定义场景。
 * 固定结果必须满足对应角色契约，否则契约层仍会按校验失败降级。
 */
@Component
public class FixtureAgentInvoker implements AgentInvoker {

    /** 当前固定夹具集版本。 */
    public static final String FIXTURE_VERSION = "2026-08-17-v2";

    /** 从输入文本解析固定响应的策略。 */
    public interface FixtureResolver {
        /** 返回固定响应文本；无匹配场景时返回 null。 */
        String resolve(AgentInvocation invocation);
    }

    private final FixtureResolver resolver;
    private final String fixtureVersion;

    public FixtureAgentInvoker() {
        this(defaultResolver(), FIXTURE_VERSION);
    }

    public FixtureAgentInvoker(FixtureResolver resolver, String fixtureVersion) {
        this.resolver = resolver;
        this.fixtureVersion = fixtureVersion;
    }

    @Override
    public AgentInvocationResult invoke(AgentInvocation invocation) {
        long startedAt = System.nanoTime();
        String text = resolver.resolve(invocation);
        if (text == null) {
            throw new AgentInvocationException("固定场景未匹配: " + invocation.agentRole(), null);
        }
        return new AgentInvocationResult(text, invocation.modelName(), (System.nanoTime() - startedAt) / 1_000_000);
    }

    @Override
    public boolean configured() {
        return true;
    }

    public String fixtureVersion() {
        return fixtureVersion;
    }

    /** 内置关键词场景：覆盖 IntentAgent / ClarifyAgent / RecommendResponseAgent 三个角色。
     *  只在用户输入段（标记之后）匹配关键词，避免系统提示词中的领域描述污染匹配。 */
    static FixtureResolver defaultResolver() {
        return invocation -> {
            String prompt = invocation.promptText();
            String role = invocation.agentRole();
            if ("IntentAgent".equals(role)) {
                return intentFixture(after(prompt, "当前这一句: "));
            }
            if ("ClarifyAgent".equals(role)) {
                return clarifyFixture(after(prompt, "用户原话: "));
            }
            if ("RecommendResponseAgent".equals(role)) {
                return recommendFixture(after(prompt, "候选资源（已排序，只能解释不能新增）: "));
            }
            if ("TrainingPlanAgent".equals(role)) {
                return trainingPlanFixture(prompt);
            }
            return null;
        };
    }

    /** 训练计划夹具只回显输入候选，并使用输入简报中的训练日和时间窗口。 */
    private static String trainingPlanFixture(String prompt) {
        Matcher candidate = Pattern.compile("\\\"exerciseId\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]+)\\\"").matcher(prompt);
        if (!candidate.find()) return null;
        String id = candidate.group(1);
        Matcher week = Pattern.compile("\\\"weekStart\\\"\\s*:\\s*\\\"(20\\d{2}-\\d{2}-\\d{2})\\\"").matcher(prompt);
        Matcher start = Pattern.compile("\\\"start\\\"\\s*:\\s*\\\"([0-2]\\d:[0-5]\\d)\\\"").matcher(prompt);
        if (!week.find() || !start.find()) return null;
        String weekStart = week.group(1);
        String startTime = start.group(1);
        StringBuilder schedule = new StringBuilder();
        Matcher day = Pattern.compile("\\\"trainingDays\\\"\\s*:\\s*\\[(.*?)\\]").matcher(prompt);
        if (day.find()) {
            Matcher value = Pattern.compile("\\\"([A-Z]+)\\\"").matcher(day.group(1));
            int index = 0;
            while (value.find() && index < 3) {
                int offset = switch (value.group(1)) {
                    case "MONDAY" -> 0;
                    case "TUESDAY" -> 1;
                    case "WEDNESDAY" -> 2;
                    case "THURSDAY" -> 3;
                    case "FRIDAY" -> 4;
                    case "SATURDAY" -> 5;
                    case "SUNDAY" -> 6;
                    default -> 0;
                };
                if (schedule.length() > 0) schedule.append(',');
                schedule.append("{\"exerciseId\":\"").append(id).append("\",\"localDate\":\"")
                        .append(LocalDate.parse(weekStart).plusDays(offset)).append("\",\"startTime\":\"").append(startTime)
                        .append("\",\"durationMinutes\":45}");
                index++;
            }
        }
        if (schedule.length() == 0) return null;
        return "{\"schedule\":[" + schedule + "]}";
    }

    /** 返回最后一个标记之后的内容；无标记返回全文。 */
    private static String after(String prompt, String marker) {
        int index = prompt.lastIndexOf(marker);
        return index < 0 ? prompt : prompt.substring(index + marker.length());
    }

    /** 意图固定结果：风险 / 综合 / 健身 / 作息 / 计划 / 饮食 场景，槽位按 Prompt 关键词提取。 */
    private static String intentFixture(String prompt) {
        String riskFlag = riskFlag(prompt);
        if (containsAny(prompt, "推荐电影", "电影推荐", "你是 AI", "你是AI", "你是 ai", "你是ai")) {
            return intentJson("OTHER", "CHAT", "", riskFlag);
        }
        if (containsAny(prompt, "综合", "整体")) {
            return intentJson("COMPOSITE", "RECOMMEND", "", riskFlag);
        }
        if (containsAny(prompt, "安排一周", "周计划", "一周计划", "一周健身计划", "一周训练计划", "一周的计划", "一周安排", "帮我安排一周")) {
            return intentJson(containsAny(prompt, "训练", "健身") ? "EXERCISE" : "MEAL", "PLAN", "", riskFlag);
        }
        if (containsAny(prompt, "换一批", "换换", "不要", "去掉")) {
            // #76 ADJUST 场景：任务按当前领域路由（MEAL/EXERCISE），排除集由编排器从会话历史类型化引用取
            return intentJson(containsAny(prompt, "训练", "健身", "俯卧撑", "深蹲") ? "EXERCISE" : "MEAL",
                    "ADJUST", "", riskFlag);
        }
        String domain;
        StringBuilder slots = new StringBuilder();
        if (containsAny(prompt, "咖啡", "咖啡因", "睡眠", "作息", "睡多久", "几点睡", "几点起", "早起", "午睡", "午休", "生物钟", "训练时段")) {
            domain = "ROUTINE";
            appendSlot(slots, "wakeTime", containsAny(prompt, "七点起", "7点起", "07:00") ? "07:00" : null);
        } else if (containsAny(prompt, "训练", "健身", "动作", "俯卧撑", "深蹲", "练", "胸肌", "胸部", "胸大肌",
                "大腿", "小腿", "腿部", "臀部", "臀肌", "臀大肌", "初学者", "新手", "轻量", "自重", "无器械", "不用器械")) {
            domain = "EXERCISE";
            appendSlot(slots, "bodyParts", exerciseBodyPart(prompt));
            appendSlot(slots, "trainingGoal", containsAny(prompt, "减肥", "瘦身") ? "减脂" : pickOne(prompt, "增肌", "减脂", "耐力", "柔韧", "力量"));
            appendSlot(slots, "difficulty", containsAny(prompt, "初学者", "新手", "轻量") ? "入门" : pickOne(prompt, "入门", "进阶", "挑战"));
            appendSlot(slots, "equipment", containsAny(prompt, "自重", "无器械", "不用器械") ? "徒手" : pickOne(prompt, "徒手", "哑铃", "杠铃", "弹力带", "壶铃"));
        } else {
            domain = "MEAL";
            String mealTime = pickOne(prompt, "早餐", "午餐", "晚餐");
            if (mealTime == null && containsAny(prompt, "晚上", "晚饭")) {
                mealTime = "晚餐";
            }
            appendSlot(slots, "mealTime", mealTime);
            appendSlot(slots, "healthGoal", pickOne(prompt, "清淡", "减脂", "高蛋白", "养胃", "均衡"));
            appendSlot(slots, "cuisine", pickOne(prompt, "川菜", "粤菜", "湘菜", "轻食", "日料", "火锅"));
            // 微辣必须先于辣匹配，否则"微辣"会被截取为"辣"
            appendSlot(slots, "taste", pickOne(prompt, "微辣", "辣", "甜"));
            appendSlot(slots, "convenience",
                    containsAny(prompt, "快的", "快点", "快速") ? "快速" : pickOne(prompt, "慢享", "外带方便"));
        }
        return intentJson(domain, "RECOMMEND", slots.toString(), riskFlag);
    }

    private static String exerciseBodyPart(String prompt) {
        if (containsAny(prompt, "胸大肌", "胸肌", "胸部", "胸")) return "胸";
        if (containsAny(prompt, "背部", "背肌", "背")) return "背";
        if (containsAny(prompt, "大腿", "小腿", "腿部", "腿")) return "腿";
        if (containsAny(prompt, "臀大肌", "臀肌", "臀部", "臀")) return "臀";
        if (containsAny(prompt, "腹部", "腹肌", "腰腹", "核心")) return "核心";
        if (containsAny(prompt, "手臂", "胳膊")) return "手臂";
        if (containsAny(prompt, "肩部", "肩膀", "肩")) return "肩";
        return null;
    }

    /** 按风险关键词返回对应 flag（44 号票：来自 RiskRuleCatalog 唯一事实来源，命中首个规则）。 */
    private static String riskFlag(String prompt) {
        return RiskRuleCatalog.firstMatch(prompt)
                .map(RiskRuleCatalog.RiskRule::flag)
                .orElse(null);
    }

    private static String intentJson(String domain, String task, String slotsJson) {
        return intentJson(domain, task, slotsJson, null);
    }

    private static String intentJson(String domain, String task, String slotsJson, String riskFlag) {
        String flags = riskFlag == null ? "[]" : "[\"" + riskFlag + "\"]";
        StringBuilder json = new StringBuilder();
        json.append("{\"domain\":\"").append(domain).append("\",\"task\":\"").append(task).append("\"");
        json.append(",\"riskFlags\":").append(flags);
        json.append(",\"slots\":{").append(slotsJson).append("}");
        json.append(",\"preferenceSignals\":[],\"confidence\":0.9}");
        return json.toString();
    }

    private static void appendSlot(StringBuilder slots, String name, String value) {
        if (value == null) {
            return;
        }
        if (slots.length() > 0) {
            slots.append(',');
        }
        slots.append('"').append(name).append("\":[\"").append(value).append("\"]");
    }

    /** 按出现顺序返回第一个命中的关键词。 */
    private static String pickOne(String prompt, String... keywords) {
        for (String keyword : keywords) {
            if (prompt.contains(keyword)) {
                return keyword;
            }
        }
        return null;
    }

    /** 澄清措辞固定结果。 */
    private static String clarifyFixture(String prompt) {
        if (containsAny(prompt, "健身", "训练")) {
            return "你今天想练哪个部位？";
        }
        if (containsAny(prompt, "睡眠", "作息")) {
            return "你平时大概几点睡、几点起？";
        }
        return "这顿主要是早餐、午餐还是晚餐？";
    }

    /** 推荐解释固定结果：从输入 Prompt 的候选 JSON 中提取前 3 个 resourceId 原样回显（#73 起按字符串输出）。 */
    private static String recommendFixture(String prompt) {
        List<String> ids = extractCandidateIds(prompt, 3);
        if (ids.isEmpty()) {
            return null;
        }
        StringBuilder json = new StringBuilder("{\"speechText\":\"根据你的需求，为你推荐了以上几款，都比较合适。\",\"reasons\":[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"resourceId\":\"").append(ids.get(i)).append("\",\"reason\":\"匹配你选择的偏好条件\"}");
        }
        json.append("]}");
        return json.toString();
    }

    /**
     * 从候选 JSON 中提取 resourceId（限制数量），支持字符串或数字两种形态，保持出现顺序。
     * #73：餐食 M1-M9 与作息 R1-R5 为字母数字种子 ID，regex 须同时覆盖字母与数字。
     */
    private static List<String> extractCandidateIds(String prompt, int limit) {
        Pattern pattern = Pattern.compile("\"resourceId\"\\s*:\\s*\"?([A-Za-z0-9_-]+)\"?");
        Matcher matcher = pattern.matcher(prompt);
        Map<String, Boolean> seen = new LinkedHashMap<>();
        while (matcher.find() && seen.size() < limit) {
            seen.put(matcher.group(1), Boolean.TRUE);
        }
        return List.copyOf(seen.keySet());
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
