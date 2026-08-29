package com.diet.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 旧饮食链路 /api/v1/diet/** 回归（餐食标签加固规格，MySQL 门控）。
 * <p>
 * 旧链路与审核读取共享 MealMapper 语句族：合并语句后 foodType 参数恒定声明，
 * 旧请求（无 foodType 字段）必须继续可用——个人餐食创建/更新写入合法 "[]"，
 * 旧 chat 搜索（经合并后的唯一检索语句）不得因 food_type NOT NULL 产生 500。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/diet_db_itest?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true",
        "spring.datasource.username=root",
        "spring.datasource.password=123456",
        "diet.agent.mode=fixture",
        "diet.resource.mode=reviewed"
})
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "itest.mysql", matches = "true")
class MysqlLegacyDietChainIntegrationTest {

    private static final long LEGACY_USER = 9681001L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM meal_item WHERE source_type = 'PERSONAL' AND owner_user_id = ?", LEGACY_USER);
    }

    @Test
    void 旧链路无foodType请求创建更新个人餐食写入合法空数组() throws Exception {
        String createBody = "{\"name\":\"旧链路番茄面\",\"mealTime\":[\"午餐\"],\"cuisine\":[\"川菜\"]}";
        MvcResult created = mockMvc.perform(post("/api/v1/diet/meals/personal")
                        .header("X-User-Id", LEGACY_USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(created.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                .contains("旧链路番茄面"));

        Long mealId = jdbc.queryForObject(
                "SELECT id FROM meal_item WHERE source_type='PERSONAL' AND owner_user_id=? "
                        + "AND name='旧链路番茄面'", Long.class, LEGACY_USER);
        assertEquals("[]", jdbc.queryForObject(
                "SELECT food_type FROM meal_item WHERE id = ?", String.class, mealId),
                "旧请求无 foodType 必须写入合法空数组，不违反 food_type NOT NULL");
        assertEquals("[\"川菜\"]", jdbc.queryForObject(
                "SELECT cuisine FROM meal_item WHERE id = ?", String.class, mealId));

        String updateBody = "{\"name\":\"旧链路番茄面改\",\"mealTime\":[\"晚餐\"],\"cuisine\":[\"川菜\"]}";
        mockMvc.perform(put("/api/v1/diet/meals/personal/" + mealId)
                        .header("X-User-Id", LEGACY_USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk());
        assertEquals("[]", jdbc.queryForObject(
                "SELECT food_type FROM meal_item WHERE id = ?", String.class, mealId),
                "旧更新请求不得破坏 food_type 合法性");

        MvcResult list = mockMvc.perform(get("/api/v1/diet/meals/personal")
                        .header("X-User-Id", LEGACY_USER))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(list.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                .contains("旧链路番茄面改"));
    }

    @Test
    void 旧链路chat搜索经合并检索语句正常返回() throws Exception {
        // fixture 模式下旧编排器确定性执行：intent → slots → 合并后的唯一检索语句 → 推荐/澄清
        MvcResult chat = mockMvc.perform(post("/api/v1/diet/chat")
                        .header("X-User-Id", LEGACY_USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"想吃火锅\",\"sessionId\":\"itest-legacy-chat\",\"sourceMode\":\"PUBLIC\"}"))
                .andReturn();
        int status = chat.getResponse().getStatus();
        assertTrue(status == 200, "旧链路 chat 不得因合并语句或 food_type NOT NULL 产生 5xx，实际 " + status);
    }
}
