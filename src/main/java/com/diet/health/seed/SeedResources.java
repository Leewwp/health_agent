package com.diet.health.seed;

import com.diet.health.module.HealthResource;
import com.diet.health.module.PlanMealCandidate;
import com.diet.health.module.RoutineFact;

import java.util.List;
import java.util.Map;

/**
 * 32 号垂直闭环的版本化种子资源。
 * <p>
 * 正式资源由 33 号 ETL 与审核子集取代；本种子只保证三品类主流程可离线演示，
 * 不得冒充正式数据。媒体统一为无图状态（mediaUrl=null），署名保留 Gym visual。
 */
public final class SeedResources {

    /** 种子资源版本，Trace 与说明文档引用。 */
    public static final String SEED_VERSION = "2026-08-10-v1";

    /** 健身种子动作（ID 9001-9009，全部 plan_ready，来源 Gym visual 数据集；9009 为明确全身标签）。 */
    public static final List<HealthResource> EXERCISES = List.of(
            exercise("9001", "俯卧撑", "胸", "徒手", "入门", List.of("胸", "手臂", "核心"), List.of("增肌", "力量", "保持健康"), "推"),
            exercise("9002", "深蹲", "腿", "徒手", "入门", List.of("腿", "臀", "核心"), List.of("增肌", "力量", "耐力", "保持健康"), "蹲"),
            exercise("9003", "平板支撑", "核心", "徒手", "入门", List.of("核心"), List.of("耐力", "保持健康"), "核心"),
            exercise("9004", "弓步蹲", "腿", "徒手", "入门", List.of("腿", "臀"), List.of("力量", "耐力", "保持健康"), "蹲"),
            exercise("9005", "臀桥", "臀", "徒手", "入门", List.of("臀", "核心", "腿"), List.of("力量", "保持健康"), "髋"),
            exercise("9006", "靠墙俯卧撑", "胸", "徒手", "入门", List.of("胸", "手臂"), List.of("力量", "保持健康"), "推"),
            exercise("9007", "弹力带划船", "背", "弹力带", "入门", List.of("背", "手臂"), List.of("力量", "保持健康"), "拉"),
            exercise("9008", "站姿提踵", "腿", "徒手", "入门", List.of("腿"), List.of("耐力", "保持健康"), "踝"),
            exercise("9009", "开合跳", "全身", "徒手", "入门", List.of("全身", "腿", "手臂", "核心"), List.of("减脂", "耐力", "保持健康"), "有氧")
    );

    /** 作息事实种子（版本化，来源见 01 号调研）。 */
    public static final List<RoutineFact> ROUTINE_FACTS = List.of(
            new RoutineFact("R1", "睡眠", "成人(18-64)每晚 7-9 小时；65 岁以上每晚 7-8 小时", "美国国家睡眠基金会", "07-09h"),
            new RoutineFact("R2", "咖啡因", "睡前 6 小时内避免咖啡因摄入，以降低入睡延迟", "美国睡眠医学会", "coffee-6h"),
            new RoutineFact("R3", "午睡", "午睡 20-30 分钟为宜，过长可能影响夜间睡眠", "美国国家睡眠基金会", "nap-20-30min"),
            new RoutineFact("R4", "训练时段", "训练时段无普遍最优，按个人昼夜节律安排即可", "睡眠与运动文献综述", "chronotype"),
            new RoutineFact("R5", "作息规律", "固定起床时间比固定入睡时间更能稳定生物钟", "睡眠卫生指南", "wake-regularity")
    );

    /**
     * 餐食候选种子（57 号票，ID M1-M9）：覆盖早/午/晚三餐并含每份热量，
     * 供周计划组合在离线演示与测试下使用；与审核餐食（数据库主键）互斥，
     * 数量小、固定、可离线复现。sortKey 为种子列表序（1-9）。
     * <p>
     * 简报补充回路（规格 v3.2）：M1-M9 的 cuisine/taste/convenience 与
     * nutritionPreferenceTags 是本轮新增的确定性标签基准（并非当前旧种子已有标签），
     * 值域取自归一器规范值，供偏好过滤的离线确定性测试：
     * M1 粥汤/清淡/快速/[]、M2 轻食/清淡/快速/[低油]、M3 粉面/咸鲜/快速/[]、
     * M4 家常/清淡/慢享/[高蛋白]、M5 家常/清淡/慢享/[高蛋白]、M6 西餐/番茄味/慢享/[]、
     * M7 轻食/清淡/快速/[低油,高蛋白]、M8 家常/清淡/快速/[低油,高蛋白]、M9 粥汤/清淡/快速/[低油]。
     */
    public static final List<PlanMealCandidate> MEAL_CANDIDATES = List.of(
            meal(1, "M1", "燕麦牛奶粥", 320, List.of("早餐"), "粥汤", "清淡", List.of(), "快速"),
            meal(2, "M2", "全麦鸡蛋三明治", 450, List.of("早餐"), "轻食", "清淡", List.of("低油"), "快速"),
            meal(3, "M3", "牛肉青菜面", 650, List.of("早餐", "午餐"), "粉面", "咸鲜", List.of(), "快速"),
            meal(4, "M4", "鸡胸肉糙米饭", 750, List.of("午餐"), "家常", "清淡", List.of("高蛋白"), "慢享"),
            meal(5, "M5", "清蒸鲈鱼套餐", 600, List.of("午餐", "晚餐"), "家常", "清淡", List.of("高蛋白"), "慢享"),
            meal(6, "M6", "番茄牛肉意面", 900, List.of("午餐"), "西餐", "番茄味", List.of(), "慢享"),
            meal(7, "M7", "鸡胸蔬菜沙拉", 450, List.of("晚餐"), "轻食", "清淡", List.of("低油", "高蛋白"), "快速"),
            meal(8, "M8", "白灼虾仁西兰花", 550, List.of("晚餐"), "家常", "清淡", List.of("低油", "高蛋白"), "快速"),
            meal(9, "M9", "南瓜小米粥配凉菜", 350, List.of("晚餐"), "粥汤", "清淡", List.of("低油"), "快速")
    );

    private SeedResources() {
    }

    private static HealthResource exercise(String id, String name, String primaryBodyPart, String equipment,
                                           String difficulty, List<String> bodyParts, List<String> goals, String movementPattern) {
        return new HealthResource(
                "EXERCISE",
                id,
                name,
                "DATASET",
                "Gym visual",
                null,
                true,
                Map.of(
                        "bodyParts", bodyParts,
                        "primaryBodyPart", List.of(primaryBodyPart),
                        "equipment", List.of(equipment),
                        "trainingGoal", goals,
                        "difficulty", List.of(difficulty),
                        "movementPattern", List.of(movementPattern)
                )
        );
    }

    private static PlanMealCandidate meal(long sortKey, String id, String name, int caloriesKcal, List<String> mealTimeTags,
                                          String cuisine, String taste, List<String> nutritionPreferences, String convenience) {
        return new PlanMealCandidate("MEAL", id, name, mealTimeTags, caloriesKcal, sortKey,
                cuisine == null ? List.of() : List.of(cuisine),
                taste == null ? List.of() : List.of(taste),
                nutritionPreferences == null ? List.of() : nutritionPreferences,
                convenience == null ? List.of() : List.of(convenience));
    }
}
