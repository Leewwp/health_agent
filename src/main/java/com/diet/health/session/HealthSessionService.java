package com.diet.health.session;

import com.diet.exception.DietException;
import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthTask;
import com.diet.health.intent.PreferenceSignal;
import com.diet.mapper.SessionMapper;
import com.diet.model.SessionRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 健康会话状态读写服务，复用 diet_sessions 表。
 * slots 列存健康槽位 Map + _meta（domain/task/riskFlags），phase 列存健康阶段名，
 * last_recommendations 列存推荐资源 ID。与旧饮食会话互不读写对方的业务字段。
 */
@Service
public class HealthSessionService {

    private static final TypeReference<Map<String, List<String>>> SLOT_MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<PreferenceSignal>> SIGNAL_LIST = new TypeReference<>() {
    };

    private final SessionMapper sessionMapper;
    private final ObjectMapper objectMapper;

    public HealthSessionService(SessionMapper sessionMapper, ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.objectMapper = objectMapper;
    }

    /** 加载或创建会话。 */
    public HealthSessionState loadOrCreate(String sessionId, Long userId) {
        if (sessionId == null || sessionId.isBlank()) {
            return create(userId);
        }
        SessionRow row = sessionMapper.findById(sessionId, userId);
        if (row == null) {
            // sessionId 已存在但归属其他匿名用户时，insert 会主键冲突，按"无权访问"返回参数错误而非 500
            try {
                insert(HealthSessionState.fresh(sessionId, userId));
            } catch (org.springframework.dao.DuplicateKeyException error) {
                throw new DietException("会话不存在或无权访问");
            }
            return HealthSessionState.fresh(sessionId, userId);
        }
        return fromRow(row);
    }

    /** 持久化会话状态（update 未命中时 insert）。 */
    public void save(HealthSessionState state) {
        int updated = sessionMapper.update(toRow(state));
        if (updated == 0) {
            insert(state);
        }
    }

    private HealthSessionState create(Long userId) {
        String sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "");
        HealthSessionState state = HealthSessionState.fresh(sessionId, userId);
        insert(state);
        return state;
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
        row.setLastRecommendations(toJson(state.lastResourceIds()));
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
        root.set("_meta", meta);
        return root.toString();
    }

    private HealthSessionState fromRow(SessionRow row) {
        try {
            JsonNode root = parseObject(row.getSlots());
            JsonNode meta = root.path("_meta");
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
                    readLongList(row.getLastRecommendations()),
                    readSignals(meta.path("preferenceSignals"))
            );
        } catch (Exception error) {
            throw new DietException("健康会话状态解析失败", error);
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

    private List<Long> readLongList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, LONG_LIST);
        } catch (Exception ignored) {
            return List.of();
        }
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
