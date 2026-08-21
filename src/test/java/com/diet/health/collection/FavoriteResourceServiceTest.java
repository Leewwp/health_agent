package com.diet.health.collection;

import com.diet.health.model.FavoriteResourceView;
import com.diet.health.module.HealthResource;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.mapper.HealthResourceFavoriteMapper;
import com.diet.model.HealthResourceFavoriteRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 独立收藏集合的类型隔离、资源校验、幂等和分页契约。 */
class FavoriteResourceServiceTest {

    private final HealthResourceFavoriteMapper mapper = mock(HealthResourceFavoriteMapper.class);
    private final HealthResourceProvider provider = mock(HealthResourceProvider.class);
    private FavoriteResourceService service;

    @BeforeEach
    void setUp() {
        service = new FavoriteResourceService(mapper, provider);
        when(provider.mealById("7")).thenReturn(Optional.of(resource("MEAL", "7")));
        when(provider.exerciseById("8")).thenReturn(Optional.of(resource("EXERCISE", "8")));
    }

    @Test
    void 添加重复收藏只写独立集合且类型隔离() {
        FavoriteResourceView first = service.add(11L, "meal", "7");
        FavoriteResourceView second = service.add(11L, "MEAL", "7");

        assertEquals("MEAL", first.resourceType());
        assertEquals("7", second.resourceId());
        verify(mapper, org.mockito.Mockito.times(2)).insertIgnore(any(HealthResourceFavoriteRow.class));
    }

    @Test
    void 取消不存在收藏幂等并拒绝未知资源类型() {
        service.remove(11L, "MEAL", "missing");
        verify(mapper).delete(11L, "MEAL", "missing");
        assertThrows(com.diet.exception.HealthApiException.class,
                () -> service.remove(11L, "ROUTINE", "R1"));
    }

    @Test
    void 添加前必须通过审核Provider解析() {
        assertThrows(com.diet.exception.HealthApiException.class,
                () -> service.add(11L, "MEAL", "missing"));
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).insertIgnore(any());
    }

    @Test
    void 分页按类型传递并保留总数() {
        HealthResourceFavoriteRow row = new HealthResourceFavoriteRow();
        row.setResourceType("EXERCISE");
        row.setResourceId("8");
        when(mapper.count(11L, "EXERCISE")).thenReturn(21);
        when(mapper.page(11L, "EXERCISE", 20, 20)).thenReturn(List.of(row));

        FavoritePage page = service.page(11L, "exercise", 2, 20);

        assertEquals(21, page.total());
        assertEquals("EXERCISE", page.items().get(0).resourceType());
        assertEquals("8", page.items().get(0).resourceId());
    }

    private HealthResource resource(String type, String id) {
        return new HealthResource(type, id, "资源 " + id, "PUBLIC", "reviewed", null,
                true, Map.of());
    }
}
