package com.diet.controller.health;

import com.diet.constants.DietConstants;
import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthTask;
import com.diet.health.model.HealthChatResponse;
import com.diet.health.model.HealthDisplayBlock;
import com.diet.health.orchestrator.HealthOrchestratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 健康聊天 HTTP 契约：正交意图字段与类型化展示块稳定序列化。 */
class HealthChatControllerTest {

    private final HealthOrchestratorService orchestrator = mock(HealthOrchestratorService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthChatController(orchestrator)).build();
    }

    @Test
    void 健身推荐响应只暴露EXERCISE展示块() throws Exception {
        HealthChatResponse response = HealthChatResponse.answer(
                "sess-1", "trace-1", HealthDomain.EXERCISE, HealthTask.RECOMMEND,
                List.of(), HealthPhase.RESPOND, "已找到适合的动作",
                List.of(new HealthDisplayBlock("EXERCISE", "9001", "俯卧撑", "PUBLIC",
                        "审核动作库", null, true, "适合入门")));
        when(orchestrator.healthChat(eq(1L), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/health/chat")
                        .requestAttr(DietConstants.USER_ID_ATTRIBUTE, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-1\",\"message\":\"胸肌\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value("EXERCISE"))
                .andExpect(jsonPath("$.task").value("RECOMMEND"))
                .andExpect(jsonPath("$.phase").value("RESPOND"))
                .andExpect(jsonPath("$.displayBlocks[0].resourceType").value("EXERCISE"));
    }

    @Test
    void OTHER闲聊响应没有健康资源() throws Exception {
        when(orchestrator.healthChat(eq(1L), any())).thenReturn(HealthChatResponse.answer(
                "sess-1", "trace-2", HealthDomain.OTHER, HealthTask.CHAT,
                List.of(), HealthPhase.RESPOND, "当前只处理健康问题", List.of()));

        mockMvc.perform(post("/api/v1/health/chat")
                        .requestAttr(DietConstants.USER_ID_ATTRIBUTE, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-2\",\"message\":\"推荐电影\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value("OTHER"))
                .andExpect(jsonPath("$.task").value("CHAT"))
                .andExpect(jsonPath("$.displayBlocks").isEmpty());
    }
}
