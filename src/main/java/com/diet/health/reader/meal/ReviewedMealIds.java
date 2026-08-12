package com.diet.health.reader.meal;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 审核餐食资源 ID 的类型化解析守卫（#69）。
 * <p>
 * 健康链的排除引用是类型化字符串 resourceId：fixture 种子为 M1-M9 等非数值 ID，
 * reviewed 库是数据库主键。进入 reviewed 数据库查询前只接受可解析的数值 ID，
 * 非法/跨模式 ID 显式忽略并记录（不抛 NumberFormatException）。
 */
public final class ReviewedMealIds {

    private ReviewedMealIds() {
    }

    /** 解析数值 ID 列表；空列表返回空集合，非法条目忽略并记录可诊断信息。 */
    public static List<Long> parseNumeric(List<String> resourceIds, Logger log, String context) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (String resourceId : resourceIds) {
            if (resourceId == null || !resourceId.matches("\\d+")) {
                log.info("忽略非数值餐食排除 ID（{}）：{}", context, resourceId);
                continue;
            }
            result.add(Long.parseLong(resourceId));
        }
        return List.copyOf(result);
    }
}
