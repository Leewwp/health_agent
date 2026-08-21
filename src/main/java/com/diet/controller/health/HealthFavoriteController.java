package com.diet.controller.health;

import com.diet.constants.DietConstants;
import com.diet.health.collection.FavoritePage;
import com.diet.health.collection.FavoriteResourceService;
import com.diet.health.model.FavoriteResourceView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 独立个人收藏接口；不复用 recommend_feedback 写入。 */
@RestController
@RequestMapping("/api/v1/health/favorites")
public class HealthFavoriteController {

    private final FavoriteResourceService service;

    public HealthFavoriteController(FavoriteResourceService service) {
        this.service = service;
    }

    @GetMapping
    public FavoritePage page(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                             @RequestParam(required = false) String resourceType,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "20") int size) {
        return service.page(userId, resourceType, page, size);
    }

    @PostMapping("/{resourceType}/{resourceId}")
    public FavoriteResourceView add(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                                    @PathVariable String resourceType, @PathVariable String resourceId) {
        return service.add(userId, resourceType, resourceId);
    }

    @DeleteMapping("/{resourceType}/{resourceId}")
    public void remove(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                       @PathVariable String resourceType, @PathVariable String resourceId) {
        service.remove(userId, resourceType, resourceId);
    }
}
