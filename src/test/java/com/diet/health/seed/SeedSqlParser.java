package com.diet.health.seed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 MySQL INSERT 语句解析器（仅用于种子数据校验测试）。
 * <p>
 * 约定：INSERT 头一行；每行一条记录（VALUES 元组独占一行，以 '(' 开头、',' 或 ');' 结尾）；
 * 字符串用单引号，内部单引号按 MySQL 规则以 '' 转义；值支持 NULL、数字、NOW()。
 */
final class SeedSqlParser {

    private SeedSqlParser() {
    }

    static Map<String, List<List<String>>> parse(String sql) {
        Map<String, List<List<String>>> result = new LinkedHashMap<>();
        String currentTable = null;
        for (String rawLine : sql.split("\n")) {
            String line = rawLine.trim();
            if (line.isBlank() || line.startsWith("--")) {
                continue;
            }
            if (line.startsWith("INSERT IGNORE INTO ")) {
                int open = line.indexOf('(');
                currentTable = line.substring("INSERT IGNORE INTO ".length(), open).trim().replace("`", "");
                int colsEnd = line.indexOf(')', open);
                if (!line.substring(colsEnd + 1).trim().startsWith("VALUES")) {
                    throw new IllegalArgumentException("seed SQL 格式错误（缺少 VALUES）: " + line);
                }
                result.computeIfAbsent(currentTable, k -> new ArrayList<>());
                continue;
            }
            if (currentTable == null || !line.startsWith("(")) {
                throw new IllegalArgumentException("seed SQL 格式错误（行不在任何 INSERT 中）: " + line);
            }
            String rowText = line.endsWith(";") ? line.substring(0, line.length() - 1) : line;
            rowText = rowText.substring(1, rowText.length() - 1);
            result.get(currentTable).add(splitRow(rowText));
        }
        return result;
    }

    static List<String> splitRow(String rowText) {
        List<String> values = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < rowText.length(); i++) {
            char c = rowText.charAt(i);
            if (inString) {
                cell.append(c);
                if (c == '\'') {
                    if (i + 1 < rowText.length() && rowText.charAt(i + 1) == '\'') {
                        cell.append('\'');
                        i++;
                    } else {
                        inString = false;
                    }
                }
            } else {
                if (c == '\'') {
                    inString = true;
                    cell.append(c);
                } else if (c == ',') {
                    values.add(cell.toString().trim());
                    cell.setLength(0);
                } else {
                    cell.append(c);
                }
            }
        }
        values.add(cell.toString().trim());
        return values.stream().map(SeedSqlParser::unquote).toList();
    }

    /** 去掉字符串两侧引号，并把 MySQL 的 '' 转义还原为单个单引号。 */
    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return value;
    }
}
