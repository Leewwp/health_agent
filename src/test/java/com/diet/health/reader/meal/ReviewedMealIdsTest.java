package com.diet.health.reader.meal;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 类型化餐食 ID 解析守卫契约（#69）：数值进入 reviewed 查询，非法/跨模式 ID 忽略并记录。 */
class ReviewedMealIdsTest {

    private static final Logger log = LoggerFactory.getLogger(ReviewedMealIdsTest.class);

    @Test
    void 数值ID全部解析() {
        assertEquals(List.of(5L, 9001L), ReviewedMealIds.parseNumeric(List.of("5", "9001"), log, "ctx"));
    }

    @Test
    void 非数值与空值忽略() {
        assertEquals(List.of(5L), ReviewedMealIds.parseNumeric(List.of("M1", "abc", "5", "", "3.5"), log, "ctx"));
    }

    @Test
    void 空列表与null返回空() {
        assertEquals(List.of(), ReviewedMealIds.parseNumeric(List.of(), log, "ctx"));
        assertEquals(List.of(), ReviewedMealIds.parseNumeric(null, log, "ctx"));
    }
}
