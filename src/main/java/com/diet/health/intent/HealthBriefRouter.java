package com.diet.health.intent;

import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthTask;
import com.diet.health.plan.MealPlanBrief;
import com.diet.health.plan.PlanBrief;
import com.diet.health.session.BriefLifecycle;
import com.diet.health.session.HealthSessionState;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 计划简报续轮的共享结构化判定（简报补充回路规格 v3.2，单一实现）。
 * <p>
 * 输入为会话状态与当轮文本，输出 {@link BriefRoutingDecision}；
 * 模型前续轮（HealthIntentRevisionService.continueBeforeAgent）、模型后修正（revise）
 * 与编排器简报门槛三处必须复用本实现，不建第二套路由状态机。
 * 裁决优先级为固定合同：风险（编排器前置执行）&gt; 明确领域切换/作息提问 &gt;
 * 替代/换一批 &gt; 明确普通推荐 &gt; 生命周期 &gt; 侧归属 &gt; 字段解析。
 * 本类只做路由判定；字段解析词与侧归属词不参与领域/任务路由。
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

    /** 共享判定入口：返回结构化判定结果。 */
    public BriefRoutingDecision decide(HealthSessionState state, String userInput) {
        String text = userInput == null ? "" : userInput.trim();
        BriefSide sessionSide = sessionSideOf(state);
        if (sessionSide == BriefSide.NONE || state == null) {
            return BriefRoutingDecision.inactive("NO_ACTIVE_BRIEF");
        }
        BriefSide targetSide = sessionSide == BriefSide.BOTH ? compositeActiveSide(state, text) : sessionSide;
        BriefLifecycle lifecycle = lifecycleOf(state, targetSide, sessionSide);
        boolean hasBriefContent = briefContentFor(state, sessionSide);
        if (lifecycle == null && !hasBriefContent) {
            // 会话根本没有计划简报上下文（如推荐澄清会话），不做简报捕获
            return BriefRoutingDecision.inactive("NO_ACTIVE_BRIEF");
        }

        // 优先级 1：明确领域切换/作息提问（证据域与当前简报侧冲突）
        HealthDomain evidence = domainEvidence(text);
        boolean evidenceConflicts = evidence != null && evidenceConflictsWith(evidence, sessionSide);
        if (evidenceConflicts) {
            BriefSide currentSide = sessionSide == BriefSide.BOTH ? compositeActiveSide(state, text) : sessionSide;
            return new BriefRoutingDecision(true, currentSide, BriefEscape.DOMAIN_OR_ROUTINE,
                    "EXPLICIT_DOMAIN_OR_ROUTINE_SWITCH", evidence);
        }

        // 优先级 2：替代/换一批
        if (containsAny(text, ALTERNATIVE_ESCAPE_WORDS)) {
            BriefSide currentSide = sessionSide == BriefSide.BOTH ? compositeActiveSide(state, text) : sessionSide;
            return new BriefRoutingDecision(true, currentSide, BriefEscape.ALTERNATIVE,
                    "ALTERNATIVE_REQUEST", null);
        }

        // 优先级 3：明确普通推荐请求（不含“计划/安排”时生效）
        if (isRecommendationRequest(text)) {
            BriefSide currentSide = sessionSide == BriefSide.BOTH ? compositeActiveSide(state, text) : sessionSide;
            return new BriefRoutingDecision(true, currentSide, BriefEscape.RECOMMEND,
                    "EXPLICIT_RECOMMEND_REQUEST", evidence);
        }

        // 优先级 4：生命周期（GENERATED/PAUSED 不捕获；显式计划词重新打开）
        boolean explicitPlanWord = HealthPlanIntentMatcher.matches(text);
        if ((lifecycle == BriefLifecycle.GENERATED || lifecycle == BriefLifecycle.PAUSED)
                && !explicitPlanWord) {
            return BriefRoutingDecision.inactive("LIFECYCLE_" + lifecycle.name());
        }

        // 优先级 5/6：侧归属与字段解析（字段解析由简报处理器执行，判定只给归属结论）
        String reason = switch (targetSide) {
            case MEAL -> "MEAL_BRIEF_FOCUS";
            case EXERCISE -> "EXERCISE_BRIEF_FOCUS";
            case BOTH -> "COMPOSITE_BOTH_NEED_SIDE_PREFIX";
            case NONE -> "NO_ACTIVE_BRIEF";
        };
        return new BriefRoutingDecision(true, targetSide, BriefEscape.NONE, reason, null);
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
