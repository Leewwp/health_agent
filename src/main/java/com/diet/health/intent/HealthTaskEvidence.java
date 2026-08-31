package com.diet.health.intent;

import com.diet.health.enums.HealthDomain;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 健康任务证据判定（ADR-0016）：区分"明确任务词"与"槽位别名"。
 * 新会话只有明确任务词才进入推荐/计划；单独出现槽位别名（如"清淡一点"、"入门徒手"）
 * 属于模糊短句，不得猜测领域。活动澄清上下文的短答继承由会话状态处理，不经过本类。
 * <p>
 * 健康助手计划引导与严格路由规格（2026-08-31）：本类是"当前轮任务硬证据"词表的
 * 唯一所有者——替代推荐词、推荐确认短语、追加计划词与聊天/能力逃生表达统一在此维护，
 * 意图规则、意图修订与编排器只消费不复制；新增任务词必须落在本类，由漂移守卫测试约束。
 * 逃生词是结构化条件下的短路优化（无任务证据 ∧ 无字段解析时的确定性判定），
 * 不构成 ADR-0018 禁止的关键词例外清单。
 */
@Component
public class HealthTaskEvidence {

    /** 餐食推荐/计划的明确任务词：进食动作、餐次名词或餐食领域名词。 */
    private static final String[] MEAL_TASK_WORDS = {
            "吃什么", "吃啥", "想吃", "能吃上", "尽快吃上", "尽快能吃上", "马上能吃", "饿了", "肚子饿",
            "食物", "吃饭", "用餐", "加餐", "菜单", "餐食", "饮食",
            "早餐", "早饭", "午餐", "午饭", "中饭", "晚餐", "晚饭",
            "菜", "餐"
    };

    /** 训练动作推荐/计划的明确任务词。 */
    private static final String[] EXERCISE_TASK_WORDS = {
            "训练", "健身", "动作", "练", "锻炼", "俯卧撑", "深蹲", "撸铁"
    };

    /** 作息事实的明确主题词。 */
    private static final String[] ROUTINE_TOPIC_WORDS = {
            "睡眠", "作息", "睡多久", "几点睡", "几点起", "早起", "午睡", "午休", "生物钟", "咖啡", "咖啡因"
    };

    /** 训练/运动时段问题：活动词 + 时间词组合才算作息事实，避免"训练计划"被误路由。 */
    private static final String[] ACTIVITY_WORDS = {"训练", "运动", "锻炼", "练"};
    private static final String[] TIME_WORDS = {"什么时候", "几点", "时段", "时间"};

    /** 通用推荐请求动词：跨领域任务证据（领域由会话上下文/模型一致性收敛）。 */
    private static final String[] RECOMMEND_REQUEST_WORDS = {"推荐"};

    /**
     * 明确替代/调整请求词（共享清单，收编意图规则与意图修订的两份漂移副本）。
     * "替代推荐"与路由器的替代逃生词保持同一口径。
     */
    private static final String[] ADJUST_REQUEST_WORDS = {
            "换一批", "换换", "再来一批", "再换", "调整一下", "替代推荐"
    };

    /** 明确排除/去掉类调整表达（仅供意图规则兜底选择领域，语义上不是"再来一批"）。 */
    private static final String[] ADJUST_REMOVAL_WORDS = {"不要", "去掉"};

    /** 推荐前预检确认短语（编排器"开始推荐"等确认语义的共享清单）。 */
    private static final String[] RECOMMENDATION_CONFIRMATION_WORDS = {
            "为我推荐", "可以推荐了", "确认推荐", "就这样推荐", "开始推荐", "按这个推荐"
    };

    /** 聊天中"追加到当前计划"类表达（编排器编辑副本入口词表）。 */
    private static final String[] APPEND_TO_PLAN_WORDS = {
            "追加到当前计划", "加入当前计划", "追加到现有计划", "加入现有计划",
            "当前计划追加", "现有计划追加", "在当前计划里加", "在现有计划里加"
    };

    /**
     * 聊天/能力逃生表达（P0 边界）：显式身份、能力与陪聊请求必须打断任何任务继承，
     * 直接进入 OTHER + CHAT。词表命中是短路优化；未命中但无任务证据的输入同样由
     * 最终闸门兜底降级，不依赖本清单完备。
     */
    private static final String[] CHAT_ESCAPE_EXPRESSIONS = {
            "你是谁", "你能帮我做什么", "可以帮我做什么", "你能做什么", "你会做什么",
            "介绍一下你自己", "自我介绍",
            "能陪我聊天吗", "可以陪我聊天吗", "陪我聊天", "陪我聊聊", "和我聊天", "聊聊天",
            "陪我说话", "说说话",
            "你好", "您好", "嗨", "哈喽", "hello", "hi", "早上好", "下午好", "晚上好", "晚安", "在吗"
    };

    /** 是否包含餐食任务的明确证据。 */
    public boolean hasMealTaskEvidence(String text) {
        return containsAny(text, MEAL_TASK_WORDS);
    }

    /** 是否包含训练任务的明确证据。 */
    public boolean hasExerciseTaskEvidence(String text) {
        return containsAny(text, EXERCISE_TASK_WORDS);
    }

    /** 是否包含作息事实的明确证据：主题词，或活动词与时间词组合。 */
    public boolean hasRoutineTaskEvidence(String text) {
        return containsAny(text, ROUTINE_TOPIC_WORDS)
                || (containsAny(text, ACTIVITY_WORDS) && containsAny(text, TIME_WORDS));
    }

    /** 是否存在任一健康品类的明确任务证据（综合/计划词由 HealthPlanIntentMatcher 另行判定）。 */
    public boolean hasAnyTaskEvidence(String text) {
        return hasMealTaskEvidence(text) || hasExerciseTaskEvidence(text) || hasRoutineTaskEvidence(text);
    }

    /** 该领域是否获得了明确任务证据；OTHER/COMPOSITE 不做槽位级判定。 */
    public boolean hasTaskEvidence(String text, HealthDomain domain) {
        return switch (domain) {
            case MEAL -> hasMealTaskEvidence(text);
            case EXERCISE -> hasExerciseTaskEvidence(text);
            case ROUTINE -> hasRoutineTaskEvidence(text);
            case COMPOSITE, OTHER -> true;
        };
    }

    /** 是否为明确的推荐请求表达（“推荐”任务动词出现即构成任务证据）。 */
    public boolean hasRecommendRequestEvidence(String text) {
        return containsAny(text, RECOMMEND_REQUEST_WORDS);
    }

    /** 是否为明确的替代/调整请求（"换一批"类）。 */
    public boolean hasAdjustRequestEvidence(String text) {
        return containsAny(text, ADJUST_REQUEST_WORDS);
    }

    /** 是否为替代/排除类调整表达（含"不要/去掉"），仅供意图规则兜底领域选择。 */
    public boolean hasAdjustOrRemovalEvidence(String text) {
        return hasAdjustRequestEvidence(text) || containsAny(text, ADJUST_REMOVAL_WORDS);
    }

    /** 是否为推荐前预检确认短语。 */
    public boolean isRecommendationConfirmation(String input) {
        return input != null && containsAny(input.replaceAll("\\s+", ""), RECOMMENDATION_CONFIRMATION_WORDS);
    }

    /** 是否为聊天中的"追加到当前计划"类表达。 */
    public boolean isAppendToCurrentPlanExpression(String input) {
        return input != null && containsAny(input, APPEND_TO_PLAN_WORDS);
    }

    /** 是否为显式聊天/能力逃生表达（打断任务继承，进入 OTHER + CHAT）。 */
    public boolean isChatEscapeExpression(String text) {
        return containsAny(text == null ? "" : text.trim(), CHAT_ESCAPE_EXPRESSIONS);
    }

    /** 漂移守卫：暴露共享清单内容，供一致性测试固定口径。 */
    public static List<String> adjustRequestWords() {
        return List.of(ADJUST_REQUEST_WORDS);
    }

    public static List<String> recommendationConfirmationWords() {
        return List.of(RECOMMENDATION_CONFIRMATION_WORDS);
    }

    public static List<String> appendToPlanWords() {
        return List.of(APPEND_TO_PLAN_WORDS);
    }

    public static List<String> chatEscapeExpressions() {
        return List.of(CHAT_ESCAPE_EXPRESSIONS);
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
