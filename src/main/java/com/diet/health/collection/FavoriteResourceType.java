package com.diet.health.collection;

/** 独立个人收藏支持的资源类型。 */
public enum FavoriteResourceType {
    MEAL,
    EXERCISE;

    public static FavoriteResourceType parse(String value) {
        try {
            return value == null ? null : valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException error) {
            return null;
        }
    }
}
