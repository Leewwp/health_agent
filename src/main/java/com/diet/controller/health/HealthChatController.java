package com.diet.controller.health;

import com.diet.constants.DietConstants;
import com.diet.health.model.HealthChatRequest;
import com.diet.health.model.HealthChatResponse;
import com.diet.health.orchestrator.HealthOrchestratorService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康聊天 HTTP 入口（规格 6.1）。
 * requestId 必填；身份由匿名 Cookie 拦截器解析。
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthChatController {

    private final HealthOrchestratorService orchestratorService;

    public HealthChatController(HealthOrchestratorService orchestratorService) {
        this.orchestratorService = orchestratorService;
    }

    @PostMapping("/chat")
    public HealthChatResponse chat(
            @RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
            @RequestBody HealthChatRequest request
    ) {
        return orchestratorService.healthChat(userId, request);
    }
}
