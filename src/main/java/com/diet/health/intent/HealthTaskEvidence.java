package com.diet.health.intent;

import com.diet.health.enums.HealthDomain;
import org.springframework.stereotype.Component;

/**
 * 健康任务证据判定（ADR-0016）：区分"明确任务词"与"槽位别名"。
 * 新会话只有明确任务词才进入推荐/计划；单独出现槽位别名（如"清淡一点"、"入门徒手"）
 * 属于模糊短句，不得猜测领域。活动澄清上下文的短答继承由会话状态处理，不经过本类。
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
    private static final String[] ACTIVITY_WORDS = {"训练", "运动", "锻炼"};
    private static final String[] TIME_WORDS = {"什么时候", "几点", "时段", "时间"};

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
