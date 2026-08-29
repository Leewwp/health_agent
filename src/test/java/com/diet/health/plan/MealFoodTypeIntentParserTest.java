package com.diet.health.plan;

import com.diet.health.intent.HealthInputNormalizer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 餐食类型未支持通道解析器矩阵（餐食标签加固规格 / ADR-0017）。
 * <p>
 * 只有显式类型形态（餐食类型：X、餐食类型是 X、想吃 X、X 类型）才允许登记词表外原值；
 * 支持中文标点、和/或/以及、多值去重、否定范围与“换成/改为”替换；
 * 模型 raw slot 中未被显式解析器认可的未知值仍丢弃（由归一器保证，这里验证解析器不放大）。
 */
class MealFoodTypeIntentParserTest {

    private final HealthInputNormalizer normalizer = new HealthInputNormalizer();
    private final MealFoodTypeIntentParser parser = new MealFoodTypeIntentParser(normalizer);

    @Test
    void 标签前缀形态登记词表外原值且不产出受支持值() {
        MealFoodTypeIntentParser.FoodTypeParse parse = parser.parse("餐食类型：生酮");
        assertTrue(parse.matched());
        assertTrue(parse.supported().isEmpty(), "词表外原值不得进入受支持列表");
        assertEquals(List.of("生酮"), parse.unsupported(), "显式形态内的原值必须诚实登记");
    }

    @Test
    void 想吃形态混合表达只把受支持值写入支持列表并登记未支持值() {
        MealFoodTypeIntentParser.FoodTypeParse parse = parser.parse("我想吃素和生酮");
        assertTrue(parse.matched());
        assertEquals(List.of("素食"), parse.supported());
        assertEquals(List.of("生酮"), parse.unsupported());
    }

    @Test
    void 否定范围不写入正向偏好() {
        MealFoodTypeIntentParser.FoodTypeParse parse = parser.parse("不想吃生酮");
        assertFalse(parse.matched(), "否定句不得命中类型形态");
        assertTrue(parse.supported().isEmpty());
        assertTrue(parse.unsupported().isEmpty());
    }

    @Test
    void 标签前缀形态内的修改词被剥除() {
        MealFoodTypeIntentParser.FoodTypeParse parse = parser.parse("餐食类型换成生酮");
        assertTrue(parse.matched());
        assertTrue(parse.supported().isEmpty());
        assertEquals(List.of("生酮"), parse.unsupported(), "修改词不得混入登记原值");
    }

    @Test
    void 中文标点和连词分隔并去重() {
        MealFoodTypeIntentParser.FoodTypeParse parse = parser.parse("餐食类型：生酮，、生酮 和 轻食");
        assertTrue(parse.matched());
        assertEquals(List.of("轻食"), parse.supported(), "受支持值来自词表别名");
        assertEquals(List.of("生酮"), parse.unsupported(), "重复与空白、中文标点必须去重");
    }

    @Test
    void 类型后缀形态登记词表外原值() {
        MealFoodTypeIntentParser.FoodTypeParse parse = parser.parse("有没有生酮类型的菜");
        assertTrue(parse.matched());
        assertEquals(List.of("生酮"), parse.unsupported());
    }

    @Test
    void 弱形态候选属于其他餐食槽位时不登记() {
        // “想吃早餐”“想吃甜的”分别属于餐次与口味槽位，不得登记为餐食类型
        MealFoodTypeIntentParser.FoodTypeParse breakfast = parser.parse("想吃早餐");
        assertFalse(breakfast.unsupported().contains("早餐"));
        MealFoodTypeIntentParser.FoodTypeParse sweet = parser.parse("想吃甜的");
        assertFalse(sweet.unsupported().contains("甜的"));
        assertFalse(sweet.unsupported().contains("甜"));
    }

    @Test
    void 受支持类型经想吃形态照常进入支持列表() {
        // 烧烤保持可达：词表内类型在显式形态中照常受支持
        MealFoodTypeIntentParser.FoodTypeParse parse = parser.parse("想吃烧烤和餐食类型是轻食的东西");
        assertTrue(parse.supported().contains("烧烤"));
        assertTrue(parse.supported().contains("轻食"));
        assertTrue(parse.unsupported().isEmpty());
    }

    @Test
    void 词表外但形态外的表达不解析() {
        assertFalse(parser.parse("随便来点吃的").matched());
        assertFalse(parser.parse("什么类型比较适合我").matched(), "碎片形态不得命中");
    }
}
