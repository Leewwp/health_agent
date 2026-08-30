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
 * <p>
 * ADR-0016 显式任务路由：新会话（无活动健康上下文）中，没有明确任务词的模糊短句
 * 不得进入任何领域推荐，统一降级为 OTHER + CHAT 领域澄清。
 * 简报补充回路（规格 v3.2）：计划简报续轮与逃生口统一经共享结构化判定
 * {@link HealthBriefRouter} 裁决，本类不持有第二套路由关键词口径。
 */
@Service
public class HealthIntentRevisionService {

    private final HealthInputNormalizer normalizer;
    private final HealthBriefRouter briefRouter;

    /** 明确任务词与槽位别名的统一判定，与意图兜底规则共享同一语义。 */
    private final HealthTaskEvidence taskEvidence = new HealthTaskEvidence();

    public HealthIntentRevisionService(HealthInputNormalizer normalizer, HealthBriefRouter briefRouter) {
        this.normalizer = normalizer;
        this.briefRouter = briefRouter;
    }

    /**
     * 在模型调用前处理状态机续轮。活跃简报中的自由文本（无逃生口命中）直接继承当前
     * PLAN 上下文；明确逃生口（推荐/替代/切域作息）返回空，由完整意图链处理；
     * 推荐澄清短答继承保持原有语义。
     */
    public Optional<HealthIntentResult> continueBeforeAgent(String userInput, HealthSessionState state) {
        if (state == null || state.domain() == null || state.task() == null) {
            return Optional.empty();
        }
        String text = userInput == null ? "" : userInput.trim();
        BriefRoutingDecision decision = briefRouter.decide(state, text);
        if (decision.escape() != BriefEscape.NONE) {
            // 逃生口：交完整意图链（推荐请求/替代推荐/明确切域或作息提问）
            return Optional.empty();
        }
        if (decision.briefActive()) {
            // 活跃简报：自由文本进入对应简报处理器，不再依赖续轮关键词清单
            Map<String, List<String>> slots = normalizer.normalize(state.domain(), text, Map.of()).slots();
            return Optional.of(HealthIntentResult.parsed(
                    state.domain(), state.task(), List.of(), slots, List.of(), 1.0));
        }
        boolean clarifyContinuation = state.phase() == HealthPhase.CLARIFY && isRecommendDomain(state.domain());
        if (clarifyContinuation) {
            Map<String, List<String>> slots = normalizer.normalize(state.domain(), text, Map.of()).slots();
            return Optional.of(HealthIntentResult.parsed(
                    state.domain(), state.task(), List.of(), slots, List.of(), 1.0));
        }
        return Optional.empty();
    }

    public Revision revise(String userInput, HealthSessionState state, HealthIntentResult raw) {
        return revise(userInput, state, raw, false);
    }

    /**
     * 意图修正；arbitrationAuthoritative 为 true 时（ADR-0018 歧义仲裁），规则已把
     * 裁决权交给受约束仲裁 Agent，模糊短句兜底不再把仲裁结果覆盖回 OTHER + CHAT。
     */
    public Revision revise(String userInput, HealthSessionState state, HealthIntentResult raw,
                           boolean arbitrationAuthoritative) {
        String text = userInput == null ? "" : userInput.trim();
        if (arbitrationAuthoritative) {
            // ADR-0018：受约束仲裁结果已通过领域/任务枚举、置信度与确定性快路径复核，
            // 规则只做槽位归一化，不再覆盖仲裁的领域与任务（模糊短句兜底也不覆盖）。
            HealthDomain arbDomain = raw.domain() == null ? HealthDomain.OTHER : raw.domain();
            HealthTask arbTask = raw.task() == null ? HealthTask.CHAT : raw.task();
            HealthInputNormalizer.NormalizationResult normalized =
                    normalizer.normalize(arbDomain, text, raw.slots());
            HealthIntentResult revised = new HealthIntentResult(arbDomain, arbTask, raw.riskFlags(),
                    normalized.slots(), raw.preferenceSignals(), raw.confidence(), raw.degraded(),
                    raw.fallbackReason());
            boolean clarifyUnsafe = normalized.requiresClarification()
                    && normalized.negatedSlots().isEmpty() && isRecommendDomain(arbDomain);
            return new Revision(revised, false, clarifyUnsafe);
        }
        boolean genericRecommendation = containsAny(text, "帮我推荐一下", "帮我推荐", "推荐一下")
                && briefRouter.domainEvidence(text) != HealthDomain.MEAL
                && briefRouter.domainEvidence(text) != HealthDomain.EXERCISE;
        if (raw.domain() == HealthDomain.COMPOSITE) {
            if (genericRecommendation) {
                HealthIntentResult clarify = new HealthIntentResult(
                        HealthDomain.OTHER, HealthTask.CHAT, raw.riskFlags(), Map.of(), raw.preferenceSignals(),
                        raw.confidence(), raw.degraded(), raw.fallbackReason());
                return new Revision(clarify, true, false);
            }
            // 综合计划请求固定为 COMPOSITE + PLAN（HealthPlanIntentMatcher 组合词命中）；
            // 无计划词的综合引导（如"综合安排饮食训练作息"）保持 RECOMMEND 引导，不进入简报收集。
            HealthTask compositeTask = HealthPlanIntentMatcher.matchesComposite(text)
                    ? HealthTask.PLAN : raw.task();
            HealthIntentResult composite = new HealthIntentResult(
                    HealthDomain.COMPOSITE, compositeTask, raw.riskFlags(), Map.of(), raw.preferenceSignals(),
                    raw.confidence(), raw.degraded(), raw.fallbackReason());
            return new Revision(composite, false, false);
        }
        HealthDomain explicitDomain = explicitDomain(text);
        boolean plan = HealthPlanIntentMatcher.matches(text);
        boolean adjust = containsAny(text, "换一批", "换换", "再来一批", "再换", "调整一下");
        // 活跃简报的续轮由共享结构化判定裁决；判定给出的逃生口不强制 PLAN 续轮
        BriefRoutingDecision routing = briefRouter.decide(state, text);
        boolean briefCaptures = routing.briefActive() && routing.escape() == BriefEscape.NONE
                && state.task() == HealthTask.PLAN && isPlanDomain(state.domain());
        // ADR-0018：孤立修改表达（无活动简报）按明确任务词进入对应域的 PLAN 创建侧，
        // 不因缺少上下文退化为作息或普通推荐；训练域修改表达（如“把训练安排到晚上七点”）
        // 进入 EXERCISE + PLAN；餐食域槽位替换（如“换成晚餐”）保持推荐语义。
        boolean modificationPlanIntent = briefRouter.hasModificationExpression(text)
                && ((briefRouter.domainEvidence(text) == HealthDomain.EXERCISE)
                || (plan && !HealthPlanIntentMatcher.matchesComposite(text)));
        HealthDomain domain = raw.domain();
        HealthTask task = raw.task();
        // ADR-0018 问题 4 修复：共享判定已把“修改表达 + 字段可解析”捕获为计划上下文时，
        // 不能被 explicitDomain 的作息启发式（“训练 + 时间”）覆盖回 ROUTINE；
        // 因此计划捕获优先于领域证据分支。
        if (briefCaptures) {
            // 简报字段更新/纠正继承当前 PLAN 领域，不能被普通意图兜底改写
            domain = state.domain();
            task = HealthTask.PLAN;
        } else if (explicitDomain != null) {
            domain = explicitDomain;
            task = domain == HealthDomain.OTHER ? HealthTask.CHAT
                    : (plan || briefCaptures || modificationPlanIntent) ? HealthTask.PLAN
                    : adjust ? HealthTask.ADJUST
                    : HealthTask.RECOMMEND;
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

        // 新会话没有明确任务词时，不得根据槽位别名或模型猜测进入领域推荐；统一领域澄清。
        // 仲裁路径（arbitrationAuthoritative）的结果已受枚举/置信度/会话状态复核，不重复降级。
        boolean noActiveHealthContext = state.domain() == null || state.domain() == HealthDomain.OTHER;
        boolean ambiguousShortPhrase = !arbitrationAuthoritative
                && noActiveHealthContext
                && task == HealthTask.RECOMMEND
                && domain != HealthDomain.OTHER
                && !taskEvidence.hasTaskEvidence(text, domain);
        if (ambiguousShortPhrase) {
            domain = HealthDomain.OTHER;
            task = HealthTask.CHAT;
        }

        HealthInputNormalizer.NormalizationResult normalized = normalizer.normalize(domain, text, raw.slots());
        if (ambiguousShortPhrase) {
            // 模糊短句不把槽位别名当作推荐条件写入会话，避免下一轮被旧值带偏。
            normalized = new HealthInputNormalizer.NormalizationResult(Map.of(), normalized.requiresClarification(),
                    normalized.negatedSlots());
        }
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
        boolean clarifyDomain = genericRecommendation || ambiguousShortPhrase;
        // 只有歧义/冲突才追问；明确否定属于本轮临时约束，不能因为缺少被否定槽位而反向追问。
        boolean clarifyUnsafe = normalized.requiresClarification()
                && normalized.negatedSlots().isEmpty() && isRecommendDomain(domain);
        return new Revision(revised, clarifyDomain, clarifyUnsafe);
    }

    private HealthDomain explicitDomain(String text) {
        if (containsAny(text, "推荐电影", "电影推荐", "你是AI", "你是ai", "你是不是AI", "你是不是ai",
                "天气怎么样", "写代码", "讲个笑话")) {
            return HealthDomain.OTHER;
        }
        // 计划简报可能同时出现“训练 + 时间”等作息词，计划领域优先于作息事实路由。
        if (HealthPlanIntentMatcher.matches(text)) {
            if (HealthPlanIntentMatcher.matchesComposite(text)) {
                return HealthDomain.COMPOSITE;
            }
            if (containsAny(text, "餐食", "饮食", "早餐", "午餐", "晚餐", "吃饭")) {
                return HealthDomain.MEAL;
            }
            if (containsAny(text, "训练", "健身", "动作", "练")) {
                return HealthDomain.EXERCISE;
            }
        }
        if (HealthPlanIntentMatcher.matchesComposite(text)) {
            return HealthDomain.COMPOSITE;
        }
        // 剩余的领域证据统一来自共享判定的证据词表，避免两份口径漂移
        return briefRouter.domainEvidence(text);
    }

    private boolean isRecommendDomain(HealthDomain domain) {
        return domain == HealthDomain.MEAL || domain == HealthDomain.EXERCISE || domain == HealthDomain.ROUTINE;
    }

    private boolean isShortReply(String text) {
        return !text.isBlank() && text.length() <= 12;
    }

    private boolean isPlanDomain(HealthDomain domain) {
        return domain == HealthDomain.EXERCISE || domain == HealthDomain.MEAL || domain == HealthDomain.COMPOSITE;
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
