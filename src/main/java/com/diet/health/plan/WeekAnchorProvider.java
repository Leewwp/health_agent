package com.diet.health.plan;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内部周锚点派生（ADR-0018「周模板与目标周」）：
 * 三个生成入口（餐食、训练、综合）与聊天触发路径在生成边界共用同一派生——
 * “生成当天所在周的周一”作为不可见内部锚点；旧会话缺失锚点时按同一规则补齐。
 * 本类是无 I/O 纯函数，不读取简报或数据库；已有锚点优先保持由调用方决定。
 */
public final class WeekAnchorProvider {

    /** 默认时区（与 WeeklyPlanService 保持一致）。 */
    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    /** 日期表达统一说明文案（ADR-0018：日期输入不改变简报语义，只给简短说明）。 */
    public static final String DATE_ONLY_EXPLANATION_COPY =
            "计划按周一至周日作为参考，不需要指定日期，已保留当前条件。";

    private static final Pattern ISO_DATE = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})");
    private static final Pattern WEEK_PHRASE = Pattern.compile("下周|本周|这周|下周一|明天开始");

    private WeekAnchorProvider() {
    }

    /** 生成当天所在周的周一（应用时区）；时区非法时回退默认时区。 */
    public static LocalDate currentMonday(String timezone) {
        return LocalDate.now(resolveZone(timezone)).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** 生成当天所在周的周一（显式时区）。 */
    public static LocalDate currentMonday(ZoneId zone) {
        return LocalDate.now(zone == null ? ZoneId.of(DEFAULT_TIMEZONE) : zone)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * 输入是否包含日期/周表达（ISO 日期或“下周/本周/这周”类短语）。
     * 仅用于“日期表达不改变简报语义”的判定，不解析出具体日期。
     */
    public static boolean hasDateExpression(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (WEEK_PHRASE.matcher(text).find()) {
            return true;
        }
        Matcher matcher = ISO_DATE.matcher(text);
        while (matcher.find()) {
            try {
                LocalDate date = LocalDate.parse(matcher.group(1));
                if (date.getDayOfWeek() == DayOfWeek.MONDAY) {
                    return true;
                }
            } catch (Exception ignored) {
                // 非法日期串不视为日期表达
            }
        }
        return false;
    }

    private static ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.of(DEFAULT_TIMEZONE);
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception ignored) {
            return ZoneId.of(DEFAULT_TIMEZONE);
        }
    }
}