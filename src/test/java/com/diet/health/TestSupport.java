package com.diet.health;

import com.diet.mapper.SlotOptionMapper;
import com.diet.service.slot.SlotOptionService;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 测试共享：基于 mock 字典构造 SlotOptionService 与 HealthSlotDictionary。 */
public final class TestSupport {

    /** 与 V1 基线一致的饮食槽位字典（测试子集）。 */
    public static final Map<String, List<String>> DIET_OPTIONS = Map.of(
            "mealTime", List.of("早餐", "早午餐", "午餐", "下午茶", "晚餐", "夜宵", "加餐", "三餐"),
            "mood", List.of("疲惫", "烦躁", "开心", "焦虑", "低落", "平静", "压力大", "没胃口", "想放松", "想奖励自己"),
            "scene", List.of("工作", "校园", "家里", "周末", "加班", "运动后", "通勤", "聚餐", "独处", "旅行"),
            "healthGoal", List.of("减脂", "清淡", "养胃", "高蛋白", "均衡", "降火", "低油", "低盐", "低糖", "补能", "增肌", "控碳水", "易消化", "暖胃"),
            "cuisine", List.of("川菜", "粤菜", "湘菜", "江浙菜", "东北菜", "鲁菜", "闽南菜", "云南菜", "新疆菜", "轻食", "西餐", "日料", "韩餐", "东南亚菜", "火锅", "烧烤", "海鲜", "素食", "家常", "小吃", "粉面", "粥汤", "快餐", "甜品"),
            "taste", List.of("清淡", "辣", "微辣", "中辣", "麻辣", "甜", "酸甜", "咸鲜", "鲜香", "酱香", "蒜香", "番茄味", "咖喱味", "奶香", "油香", "烟火气"),
            "convenience", List.of("快速", "慢享", "外带方便", "堂食舒服", "少排队", "少餐具", "一人食", "多人共享", "适合备餐", "适合边走边吃")
    );

    private TestSupport() {
    }

    /** 返回基于 mock 字典的 SlotOptionService。 */
    public static SlotOptionService slotOptionService() {
        SlotOptionMapper mapper = mock(SlotOptionMapper.class);
        when(mapper.findEnabledValues(anyString())).thenAnswer(invocation -> {
            String slot = invocation.getArgument(0);
            return DIET_OPTIONS.getOrDefault(slot, List.of());
        });
        return new SlotOptionService(mapper);
    }
}
