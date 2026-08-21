package com.diet.health.collection;

import com.diet.health.model.FavoriteResourceView;

import java.util.List;

/** 收藏服务分页响应。 */
public record FavoritePage(List<FavoriteResourceView> items, int page, int size, int total, int totalPages) {
    public FavoritePage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static FavoritePage of(List<FavoriteResourceView> items, int page, int size, int total) {
        return new FavoritePage(items, page, size, total, Math.max(1, (int) Math.ceil(total / (double) size)));
    }
}
