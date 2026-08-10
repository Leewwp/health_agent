package com.diet.agent.invoker;

import com.diet.agent.invoker.AgentInvoker.AgentInvocation;
import com.diet.agent.invoker.AgentInvoker.AgentInvocationResult;
import com.diet.health.risk.RiskRuleCatalog;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    public static final String FIXTURE_VERSION = "2026-08-10-v1";

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
            return null;
        };
    }

    /** 返回最后一个标记之后的内容；无标记返回全文。 */
    private static String after(String prompt, String marker) {
        int index = prompt.lastIndexOf(marker);
        return index < 0 ? prompt : prompt.substring(index + marker.length());
    }

    /** 意图固定结果：风险 / 健身 / 作息 / 饮食 四个场景，槽位按 Prompt 关键词提取。 */
    private static String intentFixture(String prompt) {
        String riskFlag = riskFlag(prompt);
        if (riskFlag != null) {
            String domain = containsAny(prompt, "训练", "健身", "俯卧撑", "深蹲", "练") ? "EXERCISE"
                    : containsAny(prompt, "睡眠", "作息", "睡多久", "几点睡") ? "ROUTINE"
                    : "MEAL";
            return "{\"domain\":\"" + domain + "\",\"task\":\"RECOMMEND\",\"riskFlags\":[\"" + riskFlag + "\"],\"slots\":{},\"preferenceSignals\":[],\"confidence\":0.9}";
        }
        if (containsAny(prompt, "训练", "健身", "俯卧撑", "深蹲", "练")) {
            String bodyParts = pickOne(prompt, "胸", "背", "腿", "核心", "手臂", "臀");
            String goal = pickOne(prompt, "增肌", "减脂", "耐力", "柔韧", "力量");
            StringBuilder slots = new StringBuilder();
            appendSlot(slots, "bodyParts", bodyParts);
            appendSlot(slots, "trainingGoal", goal);
            return intentJson("EXERCISE", "RECOMMEND", slots.toString());
        }
        if (containsAny(prompt, "睡眠", "作息", "睡多久", "几点睡", "几点起", "早起", "午睡", "生物钟")) {
            return intentJson("ROUTINE", "RECOMMEND", "\"wakeTime\":[\"07:00\"]");
        }
        String mealTime = pickOne(prompt, "早餐", "午餐", "晚餐");
        String healthGoal = pickOne(prompt, "清淡", "减脂", "高蛋白", "养胃", "均衡");
        String cuisine = pickOne(prompt, "川菜", "粤菜", "湘菜", "轻食", "日料", "火锅");
        String taste = pickOne(prompt, "辣", "微辣", "甜");
        String convenience = pickOne(prompt, "快速", "慢享", "外带方便");
        StringBuilder slots = new StringBuilder();
        appendSlot(slots, "mealTime", mealTime);
        appendSlot(slots, "healthGoal", healthGoal);
        appendSlot(slots, "cuisine", cuisine);
        appendSlot(slots, "taste", taste);
        appendSlot(slots, "convenience", convenience);
        return intentJson("MEAL", "RECOMMEND", slots.toString());
    }

    /** 按风险关键词返回对应 flag（44 号票：来自 RiskRuleCatalog 唯一事实来源，命中首个规则）。 */
    private static String riskFlag(String prompt) {
        return RiskRuleCatalog.firstMatch(prompt)
                .map(RiskRuleCatalog.RiskRule::flag)
                .orElse(null);
    }

    private static String intentJson(String domain, String task, String slotsJson) {
        return "{\"domain\":\"" + domain + "\",\"task\":\"" + task + "\",\"riskFlags\":[],"
                + "\"slots\":{" + slotsJson + "},\"preferenceSignals\":[],\"confidence\":0.9}";
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

    /** 推荐解释固定结果：从输入 Prompt 的候选 JSON 中提取前 3 个 resourceId 原样回显。 */
    private static String recommendFixture(String prompt) {
        List<Long> ids = extractCandidateIds(prompt, 3);
        if (ids.isEmpty()) {
            return null;
        }
        StringBuilder json = new StringBuilder("{\"speechText\":\"根据你的需求，为你推荐了以上几款，都比较合适。\",\"reasons\":[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"resourceId\":").append(ids.get(i)).append(",\"reason\":\"匹配你选择的偏好条件\"}");
        }
        json.append("]}");
        return json.toString();
    }

    /** 从候选 JSON 中提取 resourceId（限制数量），支持字符串或数字两种形态，保持出现顺序。 */
    private static List<Long> extractCandidateIds(String prompt, int limit) {
        Pattern pattern = Pattern.compile("\"resourceId\"\\s*:\\s*\"?(\\d+)\"?");
        Matcher matcher = pattern.matcher(prompt);
        Map<Long, Boolean> seen = new LinkedHashMap<>();
        while (matcher.find() && seen.size() < limit) {
            seen.put(Long.parseLong(matcher.group(1)), Boolean.TRUE);
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
