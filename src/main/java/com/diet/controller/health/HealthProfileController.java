package com.diet.controller.health;

import com.diet.constants.DietConstants;
import com.diet.health.profile.HealthProfileService;
import com.diet.health.profile.HealthProfileService.HealthProfileView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康档案 HTTP 入口（规格 6.3）：
 * GET/PUT /api/v1/health/profile；身份由匿名 Cookie 拦截器解析。
 * 响应携带能量区间与"估算值"标记，数值由确定性公式计算。
 */
@RestController
@RequestMapping("/api/v1/health/profile")
public class HealthProfileController {

    private final HealthProfileService profileService;

    public HealthProfileController(HealthProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public HealthProfileView get(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId) {
        return profileService.getProfile(userId);
    }

    @PutMapping
    public HealthProfileView put(@RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
                                 @RequestBody HealthProfileService.HealthProfileInput input) {
        return profileService.saveProfile(userId, input);
    }
}
