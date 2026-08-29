package com.diet.health.seed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 MySQL INSERT 语句解析器（仅用于种子数据校验测试）。
 * <p>
 * 约定：INSERT 头一行（支持 INSERT IGNORE 与 INSERT … AS new ON DUPLICATE KEY UPDATE 两种形态）；
 * 每行一条记录（VALUES 元组独占一行，以 '(' 开头、',' 或 ');' 结尾）；
 * 字符串用单引号，内部单引号按 MySQL 规则以 '' 转义；值支持 NULL、数字、NOW()。
 * 加固规格：解析 INSERT 头的列名并以列名取值，禁止依赖易漂移的列下标。
 */
final class SeedSqlParser {

    private SeedSqlParser() {
    }

    /** 单个 INSERT 段：列名（按声明顺序）+ 全部 VALUES 元组。 */
    record ParsedTable(String table, List<String> columns, List<List<String>> rows) {

        /** 按列名取值（加固规格：种子校验不得依赖列下标）。 */
        String value(List<String> row, String column) {
            int index = columns.indexOf(column);
            if (index < 0) {
                throw new IllegalArgumentException("种子 INSERT 缺少列 " + column + "（表 " + table + "）");
            }
            return row.get(index);
        }
    }

    static Map<String, ParsedTable> parse(String sql) {
        Map<String, ParsedTable> result = new LinkedHashMap<>();
        String currentTable = null;
        List<String> currentColumns = null;
        List<List<String>> currentRows = null;
        for (String rawLine : sql.split("\n")) {
            String line = rawLine.trim();
            if (line.isBlank() || line.startsWith("--")) {
                continue;
            }
            if (line.startsWith("INSERT IGNORE INTO ") || line.startsWith("INSERT INTO ")) {
                String prefix = line.startsWith("INSERT IGNORE INTO ") ? "INSERT IGNORE INTO " : "INSERT INTO ";
                int open = line.indexOf('(');
                currentTable = line.substring(prefix.length(), open).trim().replace("`", "");
                int colsEnd = line.indexOf(')', open);
                currentColumns = new ArrayList<>();
                for (String column : line.substring(open + 1, colsEnd).split(",")) {
                    currentColumns.add(column.trim().replace("`", ""));
                }
                if (!line.substring(colsEnd + 1).trim().startsWith("VALUES")) {
                    throw new IllegalArgumentException("seed SQL 格式错误（缺少 VALUES）: " + line);
                }
                currentRows = new ArrayList<>();
                result.put(currentTable, new ParsedTable(currentTable, currentColumns, currentRows));
                continue;
            }
            if (currentTable != null && (line.startsWith("AS ") || line.startsWith("ON DUPLICATE"))) {
                // INSERT … AS new ON DUPLICATE KEY UPDATE 尾行：语句结束
                currentTable = null;
                currentColumns = null;
                currentRows = null;
                continue;
            }
            if (currentTable == null || !line.startsWith("(")) {
                throw new IllegalArgumentException("seed SQL 格式错误（行不在任何 INSERT 中）: " + line);
            }
            String rowText = line.endsWith(";") ? line.substring(0, line.length() - 1) : line;
            rowText = rowText.substring(1, rowText.length() - 1);
            currentRows.add(splitRow(rowText));
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
