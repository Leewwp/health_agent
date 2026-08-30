package com.diet.health.intent;

import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthTask;
import com.diet.health.plan.MealPlanBrief;
import com.diet.health.plan.MealPlanBriefService;
import com.diet.health.plan.PlanBrief;
import com.diet.health.plan.PlanBriefService;
import com.diet.health.plan.WeekAnchorProvider;
import com.diet.health.session.BriefLifecycle;
import com.diet.health.session.HealthSessionState;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 计划简报续轮的共享结构化判定（简报补充回路规格 v3.2，单一实现；
 * ADR-0018「计划上下文优先路由 / 需求输入指引动态化 / 周模板与目标周」扩展）。
 * <p>
 * 输入为会话状态与当轮文本，输出 {@link BriefRoutingDecision}；
 * 模型前续轮（HealthIntentRevisionService.continueBeforeAgent）、模型后修正（revise）
 * 与编排器简报门槛三处必须复用本实现，不建第二套路由状态机。
 * <p>
 * ADR-0018 优先级：纯日期表达（只给说明，不改变简报）&gt; 计划字段修改证据（“修改表达 +
 * 对应简报可解析”，压过“活动词 + 时间词”泛化作息启发式）&gt; 明确领域切换/作息提问 &gt;
 * 替代/换一批 &gt; 明确普通推荐 &gt; 裸计划词“新建 vs 修改”澄清 &gt; 生命周期 &gt;
 * 侧归属 &gt; 字段解析。计划字段修改证据是结构化状态条件（生命周期可捕获、目标侧、
 * 修改表达、字段解析成功），不是关键词例外清单。
 * <p>
 * 路由器通过 Spring 注入简报服务，直接构造的测试实例与生产实例行为一致。
 */
@Service
public class HealthBriefRouter {

    /** 明确普通推荐请求词（唯一共享清单，替代原先漂移的多份副本）；不含“计划/安排”时生效。 */
    private static final String[] RECOMMEND_ESCAPE_WORDS = {
            "推荐", "吃什么", "吃啥", "先帮我看看", "看看今晚", "浏览"
    };

    /** 明确替代推荐词：保持 ADJUST 通道并豁免推荐预检。 */
    private static final String[] ALTERNATIVE_ESCAPE_WORDS = {"换一批", "替代推荐", "再来一批"};

    /** 社交短句小清单：未关闭会话只返回确认并保留简报；已生成态返回已生成确认。 */
    private static final String[] SOCIAL_WORDS = {
            "谢谢", "多谢", "感谢", "好的", "好嘞", "好哒", "明白了", "了解了", "知道了",
            "辛苦了", "嗯嗯", "ok", "OK", "Ok"
    };

    /** 计划字段修改表达（ADR-0018）：捕获为字段更新的结构化条件之一，不是例外关键词。 */
    private static final String[] MODIFICATION_WORDS = {
            "改为", "改成", "改到", "调整为", "调整到", "修改为", "安排到", "换成"
    };

    /** 作息事实证据（优先判定，避免“训练时段”被部位词抢先）。 */
    private static final String[] ROUTINE_WORDS = {
            "睡眠", "作息", "睡多久", "几点睡", "几点起", "早起", "午睡", "午休", "生物钟",
            "咖啡", "咖啡因"
    };
    private static final String[] ACTIVITY_WORDS = {"训练", "运动", "锻炼"};
    private static final String[] TIME_WORDS = {"什么时候", "几点", "时段", "时间"};

    /** 餐食域证据（不含裸“餐/菜”字，避免“中餐/川菜”这类偏好值被误判为切域）。 */
    private static final String[] MEAL_EVIDENCE_WORDS = {
            "吃什么", "吃啥", "想吃", "饿了", "吃饭", "加餐", "中午",
            "早餐", "早饭", "午餐", "午饭", "中饭", "晚餐", "晚饭", "餐食", "饮食"
    };

    /** 训练域证据。 */
    private static final String[] EXERCISE_EVIDENCE_WORDS = {
            "训练", "健身", "动作", "锻炼", "俯卧撑", "深蹲", "撸铁", "练"
    };

    /** 综合域证据（先于单侧证据判定）。 */
    private static final String[] COMPOSITE_EVIDENCE_WORDS = {
            "综合计划", "训练和餐食", "训练和饮食", "训练+餐食", "训练+饮食",
            "健身和餐食", "健身和饮食", "健身+餐食", "健身+饮食", "训练、餐食"
    };

    /** 综合简报显式侧前缀：餐食侧。 */
    private static final String[] MEAL_SIDE_PREFIX_WORDS = {
            "餐食", "饮食", "早餐", "午餐", "晚餐", "吃饭", "三餐", "餐次"
    };

    /** 综合简报显式侧前缀：训练侧。 */
    private static final String[] EXERCISE_SIDE_PREFIX_WORDS = {
            "训练", "健身", "动作", "练"
    };

    /** 裸餐食计划词（无任何字段内容，已有上下文时澄清新建/修改，不由模型猜测）。 */
    private static final List<String> BARE_MEAL_PLAN_WORDS = List.of(
            "餐食计划", "饮食计划", "安排餐食计划", "安排饮食计划",
            "帮我安排餐食计划", "帮我安排饮食计划", "想安排餐食计划", "想安排饮食计划"
    );

    private final PlanBriefService planBriefService;
    private final MealPlanBriefService mealPlanBriefService;

    /** Spring 入口：与意图修订服务、编排器共享同一行为实例。 */
    public HealthBriefRouter(PlanBriefService planBriefService, MealPlanBriefService mealPlanBriefService) {
        this.planBriefService = planBriefService;
        this.mealPlanBriefService = mealPlanBriefService;
    }

    /** 测试与旧调用兼容入口：等价纯词表服务实例，行为与生产一致。 */
    public HealthBriefRouter() {
        this(new PlanBriefService(new HealthInputNormalizer()), new MealPlanBriefService());
    }

    /** 共享判定入口：返回结构化判定结果。 */
    public BriefRoutingDecision decide(HealthSessionState state, String userInput) {
        String text = userInput == null ? "" : userInput.trim();
        BriefSide sessionSide = sessionSideOf(state);
        if (sessionSide == BriefSide.NONE || state == null) {
            return BriefRoutingDecision.inactive("NO_ACTIVE_BRIEF");
        }
        BriefSide passiveSide = sessionSide == BriefSide.BOTH ? compositeActiveSide(state, text) : sessionSide;
        BriefLifecycle lifecycle = lifecycleOf(state, passiveSide, sessionSide);
        boolean hasBriefContent = briefContentFor(state, sessionSide);
        if (lifecycle == null && !hasBriefContent) {
            // 会话根本没有计划简报上下文（如推荐澄清会话），不做简报捕获
            return BriefRoutingDecision.inactive("NO_ACTIVE_BRIEF");
        }

        // 优先级 0：纯日期/周表达（ADR-0018）——日期不进入简报、不改变计划语义，
        // 也不要求侧前缀；只返回统一说明。含计划意图词（计划/安排/训练等）或任一简报侧
        // 能解析出真实字段时按普通输入处理（计划请求仍走简报收集）。
        if (WeekAnchorProvider.hasDateExpression(text)
                && !containsAny(text, "计划", "安排", "训练", "健身", "动作", "练")
                && !fieldUpdateParses(state, BriefSide.MEAL, text)
                && !fieldUpdateParses(state, BriefSide.EXERCISE, text)) {
            return new BriefRoutingDecision(false, passiveSide, BriefEscape.DATE_ONLY_EXPLANATION,
                    "DATE_ONLY_EXPLANATION", null);
        }

        // 优先级 1：计划字段修改证据——活动简报上下文 + 修改表达 + 对应简报可解析出字段变化。
        // 压过“活动词 + 时间词”的泛化作息启发式（问题 4 修复：不再被路由成 ROUTINE）。
        BriefSide targetSide = sessionSide == BriefSide.BOTH ? compositeActiveSide(state, text) : sessionSide;
        if (hasModificationExpression(text)
                && fieldUpdateParses(state, targetSide, text)) {
            return new BriefRoutingDecision(true, targetSide, BriefEscape.NONE,
                    "PLAN_FIELD_MODIFICATION", null);
        }

        // 优先级 2：明确领域切换/作息提问（证据域与当前简报侧冲突）
        HealthDomain evidence = domainEvidence(text);
        boolean evidenceConflicts = evidence != null && evidenceConflictsWith(evidence, sessionSide);
        if (evidenceConflicts) {
            BriefSide currentSide = sessionSide == BriefSide.BOTH ? compositeActiveSide(state, text) : sessionSide;
            return new BriefRoutingDecision(true, currentSide, BriefEscape.DOMAIN_OR_ROUTINE,
                    "EXPLICIT_DOMAIN_OR_ROUTINE_SWITCH", evidence);
        }

        // 优先级 3：替代/换一批
        if (containsAny(text, ALTERNATIVE_ESCAPE_WORDS)) {
            BriefSide currentSide = sessionSide == BriefSide.BOTH ? compositeActiveSide(state, text) : sessionSide;
            return new BriefRoutingDecision(true, currentSide, BriefEscape.ALTERNATIVE,
                    "ALTERNATIVE_REQUEST", null);
        }

        // 优先级 4：明确普通推荐请求（不含“计划/安排”时生效）
        if (isRecommendationRequest(text)) {
            BriefSide currentSide = sessionSide == BriefSide.BOTH ? compositeActiveSide(state, text) : sessionSide;
            return new BriefRoutingDecision(true, currentSide, BriefEscape.RECOMMEND,
                    "EXPLICIT_RECOMMEND_REQUEST", evidence);
        }

        // 优先级 5：裸计划词的“新建 vs 修改”澄清（状态策略，不调用模型猜测）。
        // 已有餐食计划上下文（简报有内容或生命周期已开启）时，裸“餐食计划”无法区分新建与修改。
        if (isBareMealPlanWord(text) && mealPlanContextExists(state)) {
            return new BriefRoutingDecision(true, BriefSide.MEAL, BriefEscape.NEW_VS_MODIFY,
                    "MEAL_NEW_VS_MODIFY_CLARIFY", null);
        }

        // 优先级 6：生命周期（GENERATED/PAUSED 不捕获；显式计划词或字段修改重新打开）
        boolean explicitPlanWord = HealthPlanIntentMatcher.matches(text);
        if ((lifecycle == BriefLifecycle.GENERATED || lifecycle == BriefLifecycle.PAUSED)
                && !explicitPlanWord) {
            return BriefRoutingDecision.inactive("LIFECYCLE_" + lifecycle.name());
        }

        // 优先级 7：侧归属与字段解析（字段解析由简报处理器执行，判定只给归属结论）
        String reason = switch (targetSide) {
            case MEAL -> "MEAL_BRIEF_FOCUS";
            case EXERCISE -> "EXERCISE_BRIEF_FOCUS";
            case BOTH -> "COMPOSITE_BOTH_NEED_SIDE_PREFIX";
            case NONE -> "NO_ACTIVE_BRIEF";
        };
        return new BriefRoutingDecision(true, targetSide, BriefEscape.NONE, reason, null);
    }

    /** 文本是否包含计划字段修改表达（改为/改成/安排到等；结构化条件，供意图链共用）。 */
    public boolean hasModificationExpression(String text) {
        return containsAny(text == null ? "" : text, MODIFICATION_WORDS);
    }

    /** 裸餐食计划词（无字段内容，仅计划词本身）。 */
    public boolean isBareMealPlanWord(String text) {
        String compact = text == null ? "" : text.trim();
        return BARE_MEAL_PLAN_WORDS.contains(compact);
    }

    /**
     * 指定侧简报能否把当轮文本解析为真实字段更新（EXTRACTED/PARTIAL 且简报发生变化）。
     * 纯日期表达（日期不写入简报）与无字段变化输入不算解析成功。
     */
    public boolean fieldUpdateParses(HealthSessionState state, BriefSide side, String text) {
        if (state == null || side == null || text == null || side == BriefSide.NONE) {
            return false;
        }
        try {
            return switch (side) {
                case MEAL -> {
                    MealPlanBrief base = state.mealPlanBrief() == null
                            ? MealPlanBrief.empty() : state.mealPlanBrief();
                    MealPlanBriefService.UpdateResult result = mealPlanBriefService.update(base, text);
                    yield (result.status() == com.diet.health.plan.BriefInterpretationStatus.EXTRACTED
                            || result.status() == com.diet.health.plan.BriefInterpretationStatus.PARTIAL)
                            && !result.brief().equals(base);
                }
                case EXERCISE -> {
                    PlanBrief base = state.planBrief() == null ? PlanBrief.empty() : state.planBrief();
                    PlanBriefService.UpdateResult result = planBriefService.update(base, text);
                    yield (result.status() == com.diet.health.plan.BriefInterpretationStatus.EXTRACTED
                            || result.status() == com.diet.health.plan.BriefInterpretationStatus.PARTIAL)
                            && !result.brief().equals(base);
                }
                default -> false;
            };
        } catch (RuntimeException ignored) {
            // 解析抛错（如不支持的硬约束）不算字段修改证据
            return false;
        }
    }

    /** 该侧简报是否已有任何内容（含可选偏好与未支持记录）。 */
    private boolean briefContentFor(HealthSessionState state, BriefSide sessionSide) {
        MealPlanBrief meal = state.mealPlanBrief() == null ? MealPlanBrief.empty() : state.mealPlanBrief();
        PlanBrief training = state.planBrief() == null ? PlanBrief.empty() : state.planBrief();
        // 加固规格：餐食侧内容判定消费列表字段 cuisines/foodTypes，不再经由旧单值兼容访问器——
        // “我想吃素”这类仅含餐食类型的简报必须被判为餐食侧内容
        boolean mealContent = meal.weekStart() != null || !meal.mealTimes().isEmpty()
                || !isBlank(meal.healthGoal()) || !meal.cuisines().isEmpty() || !meal.foodTypes().isEmpty()
                || !meal.tastePreferences().isEmpty() || !isBlank(meal.convenience())
                || !meal.unsupportedPreferences().isEmpty();
        boolean trainingContent = !isBlank(training.trainingGoal()) || !training.bodyParts().isEmpty()
                || !training.equipment().isEmpty() || !isBlank(training.difficulty())
                || training.weekStart() != null || !training.trainingDays().isEmpty()
                || training.timeWindow() != null || !training.hardConstraints().isEmpty();
        return switch (sessionSide) {
            case MEAL -> mealContent;
            case EXERCISE -> trainingContent;
            case BOTH -> mealContent || trainingContent;
            case NONE -> false;
        };
    }

    /** 会话当前挂靠的简报侧：MEAL/EXERCISE/COMPOSITE 会话有侧，其余无。 */
    private BriefSide sessionSideOf(HealthSessionState state) {
        if (state == null || state.domain() == null) {
            return BriefSide.NONE;
        }
        return switch (state.domain()) {
            case MEAL -> BriefSide.MEAL;
            case EXERCISE -> BriefSide.EXERCISE;
            case COMPOSITE -> BriefSide.BOTH;
            default -> BriefSide.NONE;
        };
    }

    /** 已有餐食计划上下文：餐食简报有内容或餐食侧生命周期已开启（含 GENERATED）。 */
    private boolean mealPlanContextExists(HealthSessionState state) {
        if (state == null) {
            return false;
        }
        if (briefContentFor(state, BriefSide.MEAL)) {
            return true;
        }
        return lifecycleOf(state, BriefSide.MEAL) != null;
    }

    /** 证据域与当前简报侧是否冲突（冲突才构成“明确切换领域”逃生口）。 */
    private boolean evidenceConflictsWith(HealthDomain evidence, BriefSide sessionSide) {
        return switch (sessionSide) {
            case MEAL -> evidence != HealthDomain.MEAL;
            case EXERCISE -> evidence != HealthDomain.EXERCISE;
            // 综合会话中单侧证据交给侧归属，只有作息/其他域才算切出
            case BOTH -> evidence == HealthDomain.ROUTINE || evidence == HealthDomain.OTHER;
            case NONE -> false;
        };
    }

    /**
     * 当轮文本的领域证据：作息（含活动词+时间词组合）优先，其次综合、餐食、训练；
     * 返回 null 表示没有可用于切域判定的证据词（字段偏好值不参与路由）。
     */
    public HealthDomain domainEvidence(String text) {
        String value = text == null ? "" : text;
        if (containsAny(value, ROUTINE_WORDS)
                || (containsAny(value, ACTIVITY_WORDS) && containsAny(value, TIME_WORDS))) {
            return HealthDomain.ROUTINE;
        }
        if (containsAny(value, COMPOSITE_EVIDENCE_WORDS)) {
            return HealthDomain.COMPOSITE;
        }
        if (containsAny(value, MEAL_EVIDENCE_WORDS)) {
            return HealthDomain.MEAL;
        }
        if (containsAny(value, EXERCISE_EVIDENCE_WORDS)) {
            return HealthDomain.EXERCISE;
        }
        return null;
    }

    /** 明确普通推荐请求：共享清单命中且不含“计划/安排”（计划词优先进入简报流程）。 */
    public boolean isRecommendationRequest(String text) {
        String value = text == null ? "" : text;
        if (value.contains("计划") || value.contains("安排")) {
            return false;
        }
        return containsAny(value, RECOMMEND_ESCAPE_WORDS);
    }

    /** 是否为“换一批/替代推荐”类表达。 */
    public boolean isAlternativeRequest(String text) {
        return containsAny(text == null ? "" : text, ALTERNATIVE_ESCAPE_WORDS);
    }

    /** 社交短句判定（小清单，唯一实现）。 */
    public boolean isSocialPhrase(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        if (compact.isEmpty() || compact.length() > 6) {
            return false;
        }
        for (String word : SOCIAL_WORDS) {
            if (compact.equalsIgnoreCase(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 指定侧的生命周期：优先读取持久化值；旧会话 JSON 缺字段时按“task 为 PLAN 且领域匹配”
     * 推导 OPEN，否则无状态（返回 null）。
     */
    public BriefLifecycle lifecycleOf(HealthSessionState state, BriefSide side) {
        return lifecycleOf(state, side, side);
    }

    private BriefLifecycle lifecycleOf(HealthSessionState state, BriefSide side, BriefSide sessionSide) {
        Map<String, String> persisted = state.briefLifecycle();
        String value = persisted.get(side.name());
        if (value != null) {
            try {
                return BriefLifecycle.valueOf(value);
            } catch (Exception ignored) {
                return null;
            }
        }
        // 旧会话推导：task 为 PLAN 且领域与该侧匹配即 OPEN（简报收集进行中视为开启）
        if (state.task() == HealthTask.PLAN && sessionSideMatches(sessionSide, side)) {
            return BriefLifecycle.OPEN;
        }
        return null;
    }

    private boolean sessionSideMatches(BriefSide sessionSide, BriefSide side) {
        if (sessionSide == BriefSide.BOTH) {
            return side == BriefSide.MEAL || side == BriefSide.EXERCISE;
        }
        return sessionSide == side;
    }

    /**
     * 综合简报侧归属（固定规则）：
     * 显式侧前缀优先且允许跨侧修改；两侧皆空默认餐食；餐食未完整 → 餐食；
     * 餐食完整训练未完整 → 训练；两侧都完整且无前缀 → BOTH（不猜测，要求“餐食：/训练：”前缀）。
     */
    public BriefSide compositeActiveSide(HealthSessionState state, String userInput) {
        String text = userInput == null ? "" : userInput;
        boolean mealPrefix = containsAny(text, MEAL_SIDE_PREFIX_WORDS);
        boolean exercisePrefix = containsAny(text, EXERCISE_SIDE_PREFIX_WORDS);
        if (mealPrefix && !exercisePrefix) {
            return BriefSide.MEAL;
        }
        if (exercisePrefix && !mealPrefix) {
            return BriefSide.EXERCISE;
        }
        MealPlanBrief meal = state.mealPlanBrief() == null ? MealPlanBrief.empty() : state.mealPlanBrief();
        PlanBrief training = state.planBrief() == null ? PlanBrief.empty() : state.planBrief();
        boolean mealEmpty = meal.weekStart() == null && meal.mealTimes().isEmpty()
                && isBlank(meal.healthGoal());
        boolean trainingEmpty = training.trainingGoal() == null && training.bodyParts().isEmpty()
                && training.equipment().isEmpty() && isBlank(training.difficulty());
        if (mealEmpty && trainingEmpty) {
            return BriefSide.MEAL;
        }
        if (!meal.isComplete()) {
            return BriefSide.MEAL;
        }
        if (!training.isComplete()) {
            return BriefSide.EXERCISE;
        }
        return BriefSide.BOTH;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}