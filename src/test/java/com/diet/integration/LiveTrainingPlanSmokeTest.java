package com.diet.integration;

import com.diet.health.enums.ActivityLevel;
import com.diet.health.enums.ProfileGoal;
import com.diet.health.module.HealthResource;
import com.diet.health.plan.GenerateTrainingPlanRequest;
import com.diet.health.plan.PlanBrief;
import com.diet.health.plan.TrainingPlanGenerationResponse;
import com.diet.health.plan.TrainingPlanGenerationService;
import com.diet.health.plan.TrainingTimeWindow;
import com.diet.health.profile.HealthProfileService;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.session.HealthSessionService;
import com.diet.health.session.HealthSessionState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 发布前显式运行的真实模型 smoke；普通 CI 不依赖外部模型。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/diet_db_itest?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true",
        "spring.datasource.username=root",
        "spring.datasource.password=123456",
        "diet.agent.mode=agentscope",
        "diet.resource.mode=reviewed",
        "diet.plan-generation.timeout-ms=15000"
})
@EnabledIfSystemProperty(named = "itest.live-model", matches = "true")
class LiveTrainingPlanSmokeTest {

    private static final long USER = 880086L;
    private static final String SESSION = "sess-live-training-plan-smoke";
    private static final LocalDate WEEK_START = LocalDate.of(2026, 8, 24);

    @Autowired private DataSource dataSource;
    @Autowired private HealthProfileService profileService;
    @Autowired private HealthSessionService sessionService;
    @Autowired private HealthResourceProvider resourceProvider;
    @Autowired private TrainingPlanGenerationService generationService;
    @Autowired private ObjectMapper objectMapper;

    private JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM weekly_plan WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM health_profile WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM diet_sessions WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM diet_request_trace WHERE user_id = ?", USER);
        profileService.saveProfile(USER, new HealthProfileService.HealthProfileInput(
                30, null, 175.0, 70.0, ActivityLevel.LIGHT, ProfileGoal.MAINTAIN,
                "Asia/Shanghai", null, null));
    }

    @Test
    void 真实模型从审核候选生成可持久化训练计划() throws Exception {
        HealthResource candidate = resourceProvider.planReadyExercises().stream()
                .filter(item -> !item.tags().getOrDefault("trainingGoal", List.of()).isEmpty())
                .filter(item -> !item.tags().getOrDefault("bodyParts", List.of()).isEmpty())
                .filter(item -> !item.tags().getOrDefault("equipment", List.of()).isEmpty())
                .filter(item -> !item.tags().getOrDefault("difficulty", List.of()).isEmpty())
                .findFirst().orElseThrow();
        PlanBrief brief = new PlanBrief(
                candidate.tags().get("trainingGoal").get(0),
                List.of(candidate.tags().get("bodyParts").get(0)),
                List.of(candidate.tags().get("equipment").get(0)),
                candidate.tags().get("difficulty").get(0),
                WEEK_START,
                List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                new TrainingTimeWindow(LocalTime.of(19, 0), LocalTime.of(20, 0)),
                Map.of(), true, 1, LocalDateTime.now());
        sessionService.save(HealthSessionState.fresh(SESSION, USER).withPlanBrief(brief));

        TrainingPlanGenerationResponse response = generationService.generate(USER,
                new GenerateTrainingPlanRequest(SESSION, "live-training-plan-smoke-20260819"));

        assertEquals("AGENT", response.generationSource(), "真实模型必须成功通过结构化解析和 Guard");
        assertFalse(response.plan().items().stream().filter(item -> "EXERCISE".equals(item.resourceType())).toList().isEmpty());
        String metadata = jdbc.queryForObject(
                "SELECT generation_metadata_json FROM weekly_plan WHERE id = ?", String.class, response.planId());
        JsonNode metadataJson = objectMapper.readTree(metadata);
        assertEquals("AGENT", metadataJson.path("generationSource").asText());
        assertFalse(metadataJson.path("actualModel").asText().isBlank());
        String trace = jdbc.queryForObject(
                "SELECT trace_json FROM diet_request_trace WHERE trace_id = ?", String.class, response.traceId());
        JsonNode agentEvent = java.util.stream.StreamSupport.stream(
                        objectMapper.readTree(trace).path("events").spliterator(), false)
                .filter(event -> "AGENT_CALL".equals(event.path("eventType").asText()))
                .findFirst().orElseThrow();
        assertFalse(agentEvent.path("modelName").asText().isBlank());
        assertEquals("PARSED", objectMapper.readTree(agentEvent.path("outputPayload").asText())
                .path("parseStatus").asText());
    }
}
