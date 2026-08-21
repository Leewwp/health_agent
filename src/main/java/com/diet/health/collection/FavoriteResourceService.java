package com.diet.health.collection;

import com.diet.exception.HealthApiException;
import com.diet.health.model.FavoriteResourceView;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.mapper.HealthResourceFavoriteMapper;
import com.diet.model.HealthResourceFavoriteRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 独立个人资源收藏服务；收藏不是反馈、采纳、计划项目或推荐排序信号。 */
@Service
public class FavoriteResourceService {

    private static final int MAX_PAGE_SIZE = 100;
    private final HealthResourceFavoriteMapper mapper;
    private final HealthResourceProvider resourceProvider;

    public FavoriteResourceService(HealthResourceFavoriteMapper mapper, HealthResourceProvider resourceProvider) {
        this.mapper = mapper;
        this.resourceProvider = resourceProvider;
    }

    @Transactional
    public FavoriteResourceView add(Long userId, String type, String resourceId) {
        FavoriteResourceType resourceType = requireType(type);
        String id = requireId(resourceId);
        requireResourceExists(resourceType, id);
        LocalDateTime now = LocalDateTime.now();
        HealthResourceFavoriteRow row = new HealthResourceFavoriteRow();
        row.setUserId(userId);
        row.setResourceType(resourceType.name());
        row.setResourceId(id);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        mapper.insertIgnore(row);
        return new FavoriteResourceView(resourceType.name(), id, now);
    }

    @Transactional
    public void remove(Long userId, String type, String resourceId) {
        FavoriteResourceType resourceType = requireType(type);
        mapper.delete(userId, resourceType.name(), requireId(resourceId));
    }

    public boolean contains(Long userId, String type, String resourceId) {
        FavoriteResourceType resourceType = requireType(type);
        return mapper.exists(userId, resourceType.name(), requireId(resourceId)) > 0;
    }

    public FavoritePage page(Long userId, String type, int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "收藏分页参数无效");
        }
        String normalizedType = type == null || type.isBlank() ? null : requireType(type).name();
        int total = mapper.count(userId, normalizedType);
        List<FavoriteResourceView> items = mapper.page(userId, normalizedType, (page - 1) * size, size).stream()
                .map(row -> new FavoriteResourceView(row.getResourceType(), row.getResourceId(), row.getCreatedAt()))
                .toList();
        return FavoritePage.of(items, page, size, total);
    }

    /** 供资源 Reader 进行服务端仅收藏过滤，返回有序去重的类型化 ID。 */
    public Set<String> ids(Long userId, String type) {
        String normalizedType = type == null || type.isBlank() ? null : requireType(type).name();
        return new LinkedHashSet<>(mapper.ids(userId, normalizedType));
    }

    private FavoriteResourceType requireType(String type) {
        FavoriteResourceType parsed = FavoriteResourceType.parse(type);
        if (parsed == null) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "收藏资源类型只支持 MEAL 或 EXERCISE");
        }
        return parsed;
    }

    private String requireId(String resourceId) {
        String id = resourceId == null ? "" : resourceId.trim();
        if (id.isEmpty() || id.length() > 64) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "收藏资源 ID 无效");
        }
        return id;
    }

    /** 收藏只接受当前审核 Provider 能解析的资源，避免产生悬空收藏或跨模式 ID。 */
    private void requireResourceExists(FavoriteResourceType type, String resourceId) {
        boolean exists = switch (type) {
            case MEAL -> resourceProvider.mealById(resourceId).isPresent();
            case EXERCISE -> resourceProvider.exerciseById(resourceId).isPresent();
        };
        if (!exists) {
            throw new HealthApiException(HealthApiException.CODE_NOT_FOUND, "收藏资源不存在或未通过审核");
        }
    }
}
