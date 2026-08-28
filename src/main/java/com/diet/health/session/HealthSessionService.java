package com.diet.health.session;

import com.diet.exception.DietException;
import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthTask;
import com.diet.health.intent.PreferenceSignal;
import com.diet.health.plan.PlanBrief;
import com.diet.health.plan.MealPlanBrief;
import com.diet.health.plan.TrainingTimeWindow;
import com.diet.mapper.SessionMapper;
import com.diet.model.SessionRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 健康会话状态读写服务，复用 diet_sessions 表。
 * slots 列存健康槽位 Map + _meta（domain/task/riskFlags），phase 列存健康阶段名，
 * last_recommendations 列存类型化资源引用 JSON。与旧饮食会话互不读写对方的业务字段。
 */
@Service
public class HealthSessionService {

    private static final TypeReference<List<PreferenceSignal>> SIGNAL_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> REF_MAP = new TypeReference<>() {
    };

    /** 默认会话前缀（43 号票：HMAC 派生 ID，64 字符限制内）。 */
    private static final String DEFAULT_SESSION_PREFIX = "sess_";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int DEFAULT_SESSION_HMAC_BYTES = 28;

    private final SessionMapper sessionMapper;
    private final ObjectMapper objectMapper;

    @Value("${diet.security.session-secret:dev-only-change-me}")
    private String sessionSecret;

    public HealthSessionService(SessionMapper sessionMapper, ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 加载或创建会话（43 号票）：缺省 sessionId 时按稳定匿名身份派生默认会话，
     * 相同 userId 重复请求命中同一会话（幂等响应）；显式 sessionId 仍优先使用。
     */
    public HealthSessionState loadOrCreate(String sessionId, Long userId) {
        if (sessionId == null || sessionId.isBlank()) {
            return loadOrCreate(defaultSessionId(userId), userId);
        }
        SessionRow row = sessionMapper.findById(sessionId, userId);
        if (row == null) {
            // 并发首次创建（56 号票）：同一匿名身份的并发首请求同时 insert 同一默认会话，
            // 输方主键冲突属于合法竞态，用锁定读（FOR UPDATE）重读最新已提交数据恢复已有会话。
            // 普通读在 REPEATABLE READ 事务快照下可能看不到赢方已提交的行，锁定读始终读最新版本；
            // autocommit 下锁随语句释放，但不影响"读最新已提交"这一恢复语义。
            // 若仍未命中，说明 sessionId 被其他用户占用，保持"无权访问"拒绝语义。
            try {
                insert(HealthSessionState.fresh(sessionId, userId));
            } catch (org.springframework.dao.DuplicateKeyException error) {
                SessionRow concurrent = sessionMapper.findByIdForUpdate(sessionId, userId);
                if (concurrent != null) {
                    return fromRow(concurrent);
                }
                throw new DietException("会话不存在或无权访问");
            }
            return HealthSessionState.fresh(sessionId, userId);
        }
        return fromRow(row);
    }

    /**
     * 缺省会话 ID：HMAC(secret, "default-session:" + userId) 截断 28 字节转 hex。
     * 稳定（同一匿名身份恒同）、不可被其他匿名身份猜测（密钥在服务端）、长度 61 ≤ 64。
     */
    private String defaultSessionId(Long userId) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(sessionSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(("default-session:" + userId).getBytes(StandardCharsets.UTF_8));
            return DEFAULT_SESSION_PREFIX + HexFormat.of().formatHex(digest, 0, DEFAULT_SESSION_HMAC_BYTES);
        } catch (Exception error) {
            throw new DietException("默认会话标识派生失败", error);
        }
    }

    /** 持久化会话状态（update 未命中时 insert）。 */
    public void save(HealthSessionState state) {
        int updated = sessionMapper.update(toRow(state));
        if (updated == 0) {
            insert(state);
        }
    }

    private void insert(HealthSessionState state) {
        sessionMapper.insert(toRow(state));
    }

    private SessionRow toRow(HealthSessionState state) {
        SessionRow row = new SessionRow();
        row.setId(state.sessionId());
        row.setUserId(state.userId());
        row.setPhase(state.phase() == null ? null : state.phase().name());
        row.setSlots(toSlotsJson(state));
        row.setLastRecommendations(toJson(state.lastResources()));
        return row;
    }

    private String toSlotsJson(HealthSessionState state) {
        ObjectNode root = objectMapper.createObjectNode();
        state.slots().forEach((slotName, values) -> root.set(slotName, objectMapper.valueToTree(values)));
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("domain", state.domain() == null ? null : state.domain().name());
        meta.put("task", state.task() == null ? null : state.task().name());
        meta.put("riskFlags", objectMapper.valueToTree(state.riskFlags()));
        meta.put("preferenceSignals", objectMapper.valueToTree(state.preferenceSignals()));
        meta.put("recommendationPreflightPending", state.recommendationPreflightPending());
        meta.put("recommendationConfirmed", state.recommendationConfirmed());
        meta.put("recommendationConfirmationVersion", state.recommendationConfirmationVersion());
        root.set("_meta", meta);
        root.set("planBrief", planBriefNode(state.planBrief() == null ? PlanBrief.empty() : state.planBrief()));
        root.set("mealPlanBrief", mealPlanBriefNode(state.mealPlanBrief() == null ? MealPlanBrief.empty() : state.mealPlanBrief()));
        return root.toString();
    }

    /** 不依赖 Jackson JavaTime 模块，兼容旧测试和历史运行时的 session JSON。 */
    private JsonNode planBriefNode(PlanBrief brief) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("trainingGoal", brief.trainingGoal());
        node.set("bodyParts", objectMapper.valueToTree(brief.bodyParts()));
        node.set("equipment", objectMapper.valueToTree(brief.equipment()));
        node.put("difficulty", brief.difficulty());
        if (brief.weekStart() != null) node.put("weekStart", brief.weekStart().toString());
        node.set("trainingDays", objectMapper.valueToTree(brief.trainingDays().stream().map(Enum::name).toList()));
        if (brief.timeWindow() != null) {
            ObjectNode window = objectMapper.createObjectNode();
            window.put("start", brief.timeWindow().start().toString());
            window.put("end", brief.timeWindow().end().toString());
            node.set("timeWindow", window);
        }
        ObjectNode constraints = objectMapper.createObjectNode();
        brief.hardConstraints().forEach((key, value) -> constraints.set(key, objectMapper.valueToTree(value)));
        node.set("hardConstraints", constraints);
        // 简报没有独立确认状态（ADR-0016）：不再写入 confirmed/confirmationVersion/confirmedAt，
        // 旧会话 JSON 中的这些字段在读取时被忽略。
        if (brief.expectedField() != null) node.put("expectedField", brief.expectedField());
        node.put("failedAttempts", brief.failedAttempts());
        if (brief.partialStartTime() != null) node.put("partialStartTime", brief.partialStartTime().toString());
        return node;
    }

    private JsonNode mealPlanBriefNode(MealPlanBrief brief) {
        ObjectNode node = objectMapper.createObjectNode();
        if (brief.weekStart() != null) node.put("weekStart", brief.weekStart().toString());
        node.set("mealTimes", objectMapper.valueToTree(brief.mealTimes()));
        node.put("healthGoal", brief.healthGoal());
        return node;
    }

    private HealthSessionState fromRow(SessionRow row) {
        try {
            JsonNode root = parseObject(row.getSlots());
            JsonNode meta = root.path("_meta");
            PlanBrief planBrief = readPlanBrief(root.path("planBrief"));
            MealPlanBrief mealPlanBrief = readMealPlanBrief(root.path("mealPlanBrief"));
            Map<String, List<String>> slots = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : root.properties()) {
                if ("_meta".equals(entry.getKey()) || !entry.getValue().isArray()) {
                    continue;
                }
                List<String> values = objectMapper.readValue(entry.getValue().toString(), new TypeReference<List<String>>() {
                });
                if (!values.isEmpty()) {
                    slots.put(entry.getKey(), values);
                }
            }
            return new HealthSessionState(
                    row.getId(),
                    row.getUserId(),
                    parsePhase(row.getPhase()),
                    parseEnum(meta.path("domain").asText(null), HealthDomain.class),
                    parseEnum(meta.path("task").asText(null), HealthTask.class),
                    readStringList(meta.path("riskFlags")),
                    slots,
                    readResourceRefs(row.getLastRecommendations()),
                    readSignals(meta.path("preferenceSignals")),
                    planBrief,
                    mealPlanBrief,
                    meta.path("recommendationPreflightPending").asBoolean(false),
                    meta.path("recommendationConfirmed").asBoolean(false),
                    meta.path("recommendationConfirmationVersion").asLong(0)
            );
        } catch (Exception error) {
            throw new DietException("健康会话状态解析失败", error);
        }
    }

    private PlanBrief readPlanBrief(JsonNode node) {
        if (node == null || !node.isObject() || node.isMissingNode()) {
            return PlanBrief.empty();
        }
        try {
            String goal = textOrNull(node, "trainingGoal");
            List<String> bodyParts = readStringList(node.path("bodyParts"));
            List<String> equipment = readStringList(node.path("equipment"));
            String difficulty = textOrNull(node, "difficulty");
            LocalDate weekStart = node.hasNonNull("weekStart") ? LocalDate.parse(node.get("weekStart").asText()) : null;
            List<DayOfWeek> days = new ArrayList<>();
            if (node.path("trainingDays").isArray()) {
                node.path("trainingDays").forEach(value -> {
                    try { days.add(DayOfWeek.valueOf(value.asText())); } catch (Exception ignored) { }
                });
            }
            TrainingTimeWindow window = null;
            JsonNode windowNode = node.path("timeWindow");
            if (windowNode.isObject() && windowNode.hasNonNull("start") && windowNode.hasNonNull("end")) {
                window = new TrainingTimeWindow(LocalTime.parse(windowNode.get("start").asText()), LocalTime.parse(windowNode.get("end").asText()));
            }
            Map<String, List<String>> constraints = new LinkedHashMap<>();
            windowNode = node.path("hardConstraints");
            if (windowNode.isObject()) {
                windowNode.fields().forEachRemaining(entry -> constraints.put(entry.getKey(), readStringList(entry.getValue())));
            }
            // 旧会话 JSON 可能仍带 confirmed/confirmationVersion/confirmedAt，读取时忽略（无确认语义）。
            String expectedField = textOrNull(node, "expectedField");
            int failedAttempts = node.path("failedAttempts").asInt(0);
            LocalTime partialStartTime = node.hasNonNull("partialStartTime")
                    ? LocalTime.parse(node.get("partialStartTime").asText()) : null;
            return new PlanBrief(goal, bodyParts, equipment, difficulty, weekStart, days, window, constraints,
                    expectedField, failedAttempts, partialStartTime);
        } catch (Exception ignored) {
            return PlanBrief.empty();
        }
    }

    private MealPlanBrief readMealPlanBrief(JsonNode node) {
        if (node == null || !node.isObject() || node.isMissingNode()) return MealPlanBrief.empty();
        try {
            LocalDate weekStart = node.hasNonNull("weekStart") ? LocalDate.parse(node.get("weekStart").asText()) : null;
            List<String> mealTimes = readStringList(node.path("mealTimes"));
            String goal = textOrNull(node, "healthGoal");
            // 旧会话 JSON 可能仍带 confirmed/confirmationVersion/confirmedAt，读取时忽略（无确认语义）。
            return new MealPlanBrief(weekStart, mealTimes, goal);
        } catch (Exception ignored) {
            return MealPlanBrief.empty();
        }
    }

    private String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) && !node.get(field).asText().isBlank() ? node.get(field).asText() : null;
    }

    /**
     * 读取 last_recommendations（43 号票）：新版为 {type,id} 对象数组；
     * 旧版为数字数组，读取时按无类型遗留引用兼容（不丢数据、不崩溃）。
     */
    private List<SessionResourceRef> readResourceRefs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode array = objectMapper.readTree(json);
            List<SessionResourceRef> refs = new ArrayList<>();
            if (array.isArray()) {
                for (JsonNode node : array) {
                    if (node.isObject()) {
                        Map<String, String> map = objectMapper.convertValue(node, REF_MAP);
                        if (map.get("id") != null && !map.get("id").isBlank()) {
                            refs.add(new SessionResourceRef(map.get("type"), map.get("id")));
                        }
                    } else if (node.isNumber()) {
                        refs.add(SessionResourceRef.legacy(node.asText()));
                    }
                }
            }
            return List.copyOf(refs);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private JsonNode parseObject(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(json);
    }

    private List<String> readStringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        node.forEach(item -> {
            if (item.isTextual() && !item.asText().isBlank()) {
                result.add(item.asText());
            }
        });
        return result;
    }

    private List<PreferenceSignal> readSignals(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(node.toString(), SIGNAL_LIST);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception error) {
            throw new DietException("健康会话状态 JSON 序列化失败", error);
        }
    }

    private HealthPhase parsePhase(String phase) {
        if (phase == null || phase.isBlank()) {
            return HealthPhase.START;
        }
        try {
            return HealthPhase.valueOf(phase);
        } catch (Exception ignored) {
            return HealthPhase.START;
        }
    }

    private <E extends Enum<E>> E parseEnum(String value, Class<E> type) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (Exception ignored) {
            return null;
        }
    }
}
