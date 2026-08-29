package com.diet.health.recommend;

import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthTask;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 推荐前预检确认指纹（简报补充回路规格 v3.2）：
 * UTF-8 canonical 输入串
 * <pre>
 * domain=&lt;值&gt;
 * task=&lt;值&gt;
 * slots=&lt;按键名字典序、列表去重并按规范值排序的 canonical JSON&gt;
 * resourceVersion=&lt;值&gt;
 * </pre>
 * 空值显式编码为空串后计算 SHA-256。槽位、领域、任务或资源版本变化都会使旧指纹失效；
 * 新会话不携带旧指纹；旧会话只有布尔确认而无指纹时按未确认处理。
 */
public final class ConfirmationFingerprints {

    private ConfirmationFingerprints() {
    }

    /** 推荐确认指纹：领域 + 任务 + 规范化有效槽位 + 资源版本。 */
    public static String recommendation(HealthDomain domain, HealthTask task,
                                        Map<String, List<String>> slots, String resourceVersion) {
        String canonical = "domain=" + orEmpty(domain == null ? null : domain.name())
                + "\ntask=" + orEmpty(task == null ? null : task.name())
                + "\nslots=" + canonicalSlots(slots)
                + "\nresourceVersion=" + orEmpty(resourceVersion);
        return sha256Hex(canonical);
    }

    /**
     * 槽位 canonical JSON：按键名字典序排序；列表值去重并按规范值（码点序）排序；
     * 空槽位显式编码为 "{}"。
     */
    static String canonicalSlots(Map<String, List<String>> slots) {
        Map<String, List<String>> safe = slots == null ? Map.of() : slots;
        Map<String, List<String>> sorted = new TreeMap<>();
        safe.forEach((key, values) -> {
            if (values == null || values.isEmpty()) {
                return;
            }
            sorted.put(key, values.stream().distinct().sorted(String::compareTo).toList());
        });
        if (sorted.isEmpty()) {
            return "{}";
        }
        StringBuilder json = new StringBuilder("{");
        boolean firstKey = true;
        for (Map.Entry<String, List<String>> entry : sorted.entrySet()) {
            if (!firstKey) {
                json.append(',');
            }
            firstKey = false;
            json.append(quote(entry.getKey())).append(':').append('[');
            boolean firstValue = true;
            for (String value : entry.getValue()) {
                if (!firstValue) {
                    json.append(',');
                }
                firstValue = false;
                json.append(quote(value));
            }
            json.append(']');
        }
        return json.append('}').toString();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 摘要算法不可用", error);
        }
    }
}
