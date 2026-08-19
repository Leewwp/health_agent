package com.diet.health.intent;

import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthTask;
import com.diet.health.session.HealthSessionState;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 健康意图修正：以当前轮明确证据优先，随后处理澄清继承、调整上下文和安全澄清。
 * 本类不读取资源或数据库，只修正意图与槽位。
 */
@Service
public class HealthIntentRevisionService {

    private final HealthInputNormalizer normalizer;

    public HealthIntentRevisionService(HealthInputNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    /**
     * 在模型调用前处理状态机续轮。澄清短答和训练简报字段只解析当前缺失信息，
     * 不重新识别领域；用户明确切换领域时返回空，由完整意图链处理。
     */
    public Optional<HealthIntentResult> continueBeforeAgent(String userInput, HealthSessionState state) {
        if (state == null || state.domain() == null || state.task() == null) {
            return Optional.empty();
        }
        String text = userInput == null ? "" : userInput.trim();
        HealthDomain explicit = explicitDomain(text);
        boolean compositePlanReply = state.domain() == HealthDomain.COMPOSITE
                && state.task() == HealthTask.PLAN && isCompositeBriefReply(text);
        if (explicit != null && explicit != state.domain() && !compositePlanReply) {
            return Optional.empty();
        }
        boolean planContinuation = isPlanDomain(state.domain()) && state.task() == HealthTask.PLAN;
        if (planContinuation && looksLikeRecommendationRequest(text)) {
            return Optional.empty();
        }
        boolean clarifyContinuation = state.phase() == HealthPhase.CLARIFY && isRecommendDomain(state.domain());
        if (!planContinuation && !clarifyContinuation) {
            return Optional.empty();
        }
        Map<String, List<String>> slots = normalizer.normalize(state.domain(), text, Map.of()).slots();
        return Optional.of(HealthIntentResult.parsed(
                state.domain(), state.task(), List.of(), slots, List.of(), 1.0));
    }

    public Revision revise(String userInput, HealthSessionState state, HealthIntentResult raw) {
        String text = userInput == null ? "" : userInput.trim();
        boolean genericRecommendation = containsAny(text, "帮我推荐一下", "帮我推荐", "推荐一下")
                && explicitDomain(text) == null;
        if (raw.domain() == HealthDomain.COMPOSITE) {
            if (genericRecommendation) {
                HealthIntentResult clarify = new HealthIntentResult(
                        HealthDomain.OTHER, HealthTask.CHAT, raw.riskFlags(), Map.of(), raw.preferenceSignals(),
                        raw.confidence(), raw.degraded(), raw.fallbackReason());
                return new Revision(clarify, true, false);
            }
            HealthIntentResult composite = new HealthIntentResult(
                    HealthDomain.COMPOSITE, raw.task(), raw.riskFlags(), Map.of(), raw.preferenceSignals(),
                    raw.confidence(), raw.degraded(), raw.fallbackReason());
            return new Revision(composite, false, false);
        }
        HealthDomain explicitDomain = explicitDomain(text);
        boolean plan = HealthPlanIntentMatcher.matches(text);
        boolean adjust = containsAny(text, "换一批", "换换", "再来一批", "再换", "调整一下");
        HealthDomain domain = raw.domain();
        HealthTask task = raw.task();
        boolean planContinuation = state.task() == HealthTask.PLAN
                && isPlanDomain(state.domain())
                && (state.phase() == HealthPhase.CLARIFY || isPlanContinuation(text)
                || HealthPlanIntentMatcher.matches(text))
                && !looksLikeRecommendationRequest(text);
        if (explicitDomain != null) {
            domain = explicitDomain;
            task = domain == HealthDomain.OTHER ? HealthTask.CHAT
                    : (plan || planContinuation) ? HealthTask.PLAN
                    : adjust ? HealthTask.ADJUST
                    : HealthTask.RECOMMEND;
        } else if (planContinuation) {
            // 训练简报确认/纠正是当前 PLAN 上下文的继续输入，不能被普通意图兜底改写。
            domain = HealthDomain.EXERCISE;
            task = HealthTask.PLAN;
        } else if (adjust && isRecommendDomain(state.domain())) {
            domain = state.domain();
            task = HealthTask.ADJUST;
        } else if (state.phase() == HealthPhase.CLARIFY && isRecommendDomain(state.domain())) {
            HealthInputNormalizer.NormalizationResult inherited = normalizer.normalize(state.domain(), text, raw.slots());
            if (!inherited.slots().isEmpty() || isShortReply(text)) {
                domain = state.domain();
                task = state.task() == HealthTask.ADJUST ? HealthTask.ADJUST : HealthTask.RECOMMEND;
            }
        }

        if (HealthPlanIntentMatcher.matchesComposite(text)) {
            domain = HealthDomain.COMPOSITE;
            task = HealthTask.PLAN;
        } else if (plan && domain != HealthDomain.OTHER) {
            task = HealthTask.PLAN;
        }
        if (domain == null) {
            domain = HealthDomain.OTHER;
            task = HealthTask.CHAT;
        }

        HealthInputNormalizer.NormalizationResult normalized = normalizer.normalize(domain, text, raw.slots());
        HealthIntentResult revised = new HealthIntentResult(
                domain,
                task,
                raw.riskFlags(),
                normalized.slots(),
                raw.preferenceSignals(),
                raw.confidence(),
                raw.degraded(),
                raw.fallbackReason()
        );
        boolean clarifyDomain = genericRecommendation;
        boolean clarifyUnsafe = normalized.requiresClarification() && isRecommendDomain(domain);
        return new Revision(revised, clarifyDomain, clarifyUnsafe);
    }

    private HealthDomain explicitDomain(String text) {
        if (containsAny(text, "推荐电影", "电影推荐", "你是AI", "你是ai", "你是不是AI", "你是不是ai",
                "天气怎么样", "写代码", "讲个笑话")) {
            return HealthDomain.OTHER;
        }
        if (HealthPlanIntentMatcher.matchesComposite(text)) {
            return HealthDomain.COMPOSITE;
        }
        if (containsAny(text, "咖啡", "咖啡因", "睡眠", "作息", "睡多久", "几点睡", "几点起", "午睡", "午休",
                "生物钟", "训练时段")
                || (containsAny(text, "训练", "运动") && containsAny(text, "什么时候", "几点", "时段", "时间"))) {
            return HealthDomain.ROUTINE;
        }
        if (containsAny(text, "吃什么", "想吃", "早餐", "早饭", "午餐", "午饭", "中饭", "中午", "晚餐", "晚饭", "餐食", "饮食")) {
            return HealthDomain.MEAL;
        }
        if (containsAny(text, "健身", "训练", "动作", "俯卧撑", "深蹲", "练")
                || !normalizer.normalize(HealthDomain.EXERCISE, text, Map.of()).slots().isEmpty()) {
            return HealthDomain.EXERCISE;
        }
        return null;
    }

    private boolean isRecommendDomain(HealthDomain domain) {
        return domain == HealthDomain.MEAL || domain == HealthDomain.EXERCISE || domain == HealthDomain.ROUTINE;
    }

    private boolean isShortReply(String text) {
        return !text.isBlank() && text.length() <= 12;
    }

    private boolean isPlanContinuation(String text) {
        return containsAny(text, "确认训练偏好", "确认简报", "按这个生成", "改成", "换成",
                "目标周", "周一", "周二", "周三", "周四", "周五", "周六", "周日",
                "徒手", "哑铃", "杠铃", "弹力带", "入门", "进阶", "挑战", "增肌", "减脂", "耐力", "力量",
                "确认餐食", "确认饮食", "早餐", "午餐", "晚餐", "下周", "本周", "这周");
    }

    private boolean isPlanDomain(HealthDomain domain) {
        return domain == HealthDomain.EXERCISE || domain == HealthDomain.MEAL || domain == HealthDomain.COMPOSITE;
    }

    private boolean isCompositeBriefReply(String text) {
        if (containsAny(text, "推荐", "吃什么", "浏览", "换一批")) return false;
        return containsAny(text, "训练", "健身", "练", "胸", "背", "腿", "核心", "徒手", "哑铃", "杠铃",
                "入门", "进阶", "挑战", "早餐", "午餐", "晚餐", "下周", "本周", "这周", "确认", "目标周",
                "一三五", "二四六", "时间", "点", ":");
    }

    private boolean looksLikeRecommendationRequest(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return containsAny(text, "推荐", "吃什么", "先帮我看看", "看看今晚", "浏览")
                && !containsAny(text, "计划", "安排");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    public record Revision(HealthIntentResult intent, boolean clarifyDomain, boolean clarifyUnsafe) {
    }
}
