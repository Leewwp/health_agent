package com.diet.health.plan;

import com.diet.health.intent.HealthInputNormalizer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 确定性受限菜系意图解析器：不依赖模型、不参与路由，覆盖受支持/未支持/范围外/否定四态。 */
class MealCuisineIntentParserTest {

    private final MealCuisineIntentParser parser =
            new MealCuisineIntentParser(new HealthInputNormalizer());

    @Test
    void 显式标签形态解析受支持菜系() {
        MealCuisineIntentParser.CuisineParse colon = parser.parse("菜系：川菜");
        assertTrue(colon.matched());
        assertEquals(List.of("川菜"), colon.supported());
        assertTrue(colon.unsupported().isEmpty());

        assertTrue(parser.parse("菜系是 粤菜").supported().contains("粤菜"));
        assertTrue(parser.parse("我喜欢粤菜菜系").supported().contains("粤菜"));
    }

    @Test
    void 封闭超类词表命中时原值登记未支持() {
        // “中餐”不在别名表（库中无此标签），但属于封闭超类词表 → 原值进入未支持集合
        MealCuisineIntentParser.CuisineParse parse = parser.parse("我喜欢中餐");
        assertTrue(parse.matched());
        assertTrue(parse.supported().isEmpty());
        assertEquals(List.of("中餐"), parse.unsupported());
    }

    @Test
    void 中餐和川菜采用受支持的川菜并记录中餐() {
        MealCuisineIntentParser.CuisineParse parse = parser.parse("中餐、川菜");
        assertTrue(parse.matched());
        assertEquals(List.of("川菜"), parse.supported());
        assertEquals(List.of("中餐"), parse.unsupported());
    }

    @Test
    void 否定范围剥除后不写正向偏好() {
        assertFalse(parser.parse("不喜欢中餐，喜欢清淡").matched());
        assertFalse(parser.parse("不要川菜").matched());
        MealCuisineIntentParser.CuisineParse mixed = parser.parse("不吃火锅，想要清淡的家常菜");
        assertFalse(mixed.supported().contains("火锅"), "否定范围内的值不得进入正向偏好");
    }

    @Test
    void 范围外表达不解析() {
        // 无标签形态、无超类词 → 解析器不命中（由简报服务走 INVALID 指引）
        assertFalse(parser.parse("我想吃清淡的").matched());
        assertFalse(parser.parse("烹饪时间短一些").matched());
        assertFalse(parser.parse("清淡、高蛋白").matched(), "口味/营养偏好不是菜系表达");
    }

    @Test
    void 支持中文标点和和或以及分隔() {
        assertEquals(List.of("川菜"), parser.parse("菜系川菜和家常").supported());
        assertEquals(List.of("家常"), parser.parse("菜系川菜和家常").unsupported());
        assertEquals(List.of("川菜", "粤菜"), parser.parse("川菜或粤菜菜系").supported());
    }

    @Test
    void 可选菜系列表来自归一器别名表且不含中餐() {
        List<String> cuisines = parser.supportedCuisines();
        assertTrue(cuisines.contains("川菜"));
        assertFalse(cuisines.contains("家常"), "家常属于餐食类型，不是菜系");
        assertFalse(cuisines.contains("中餐"), "中餐是非库标签，不得加入受支持菜系别名表");
    }
}
