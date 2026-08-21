package com.diet.health.feedback;

/** 类型化反馈常量（41 号票 + #65）：action 白名单、资源类型与来源标识。 */
public final class FeedbackConstants {

    public static final String ACTION_LIKE = "LIKE";
    public static final String ACTION_DISLIKE = "DISLIKE";
    public static final String ACTION_FAVORITE = "FAVORITE";
    public static final String ACTION_UNFAVORITE = "UNFAVORITE";
    public static final String ACTION_ADOPT = "ADOPT";
    public static final String ACTION_REDUCE_RECOMMENDATION = "REDUCE_RECOMMENDATION";
    public static final String ACTION_UNDO_REDUCE_RECOMMENDATION = "UNDO_REDUCE_RECOMMENDATION";

    public static final String RESOURCE_TYPE_MEAL = "MEAL";
    public static final String RESOURCE_TYPE_EXERCISE = "EXERCISE";
    public static final String RESOURCE_TYPE_ROUTINE = "ROUTINE";

    public static final String SOURCE_LEGACY_DIET = "LEGACY_DIET";
    public static final String SOURCE_HEALTH_CHAT = "HEALTH_CHAT";

    private FeedbackConstants() {
    }
}
