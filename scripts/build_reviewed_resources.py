#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
审核资源 ETL（33 号票）：离线候选池 + 人工审核映射 → 幂等 seed SQL + 导入报告。

输入（均为本地文件，数据来源版本见报告）：
  - 餐食候选池  data/meal/processed/healthy_recipes_1000.csv（prepare_meal_dataset.py 产物，不提交）
  - 餐食中文名映射 scripts/meal_curation/meal_name_zh.csv（人工审核，提交）
  - 槽位标签映射  scripts/meal_curation/meal_tag_map.json（人工审核，提交）
  - 动作数据集  data/exercise/raw/exercises.json（gym-visual-exercises-dataset，MIT，可经
    https://cdn.jsdelivr.net/gh/hasaneyldrm/exercises-dataset@main/data/exercises.json 下载，不提交）
  - 动作审核映射  data/exercise/curated/exercise_plan_ready.csv（人工审核，提交）
  - 作息事实      data/routine/routine_facts.csv（来源见 01 号调研，提交）

输出：
  - src/main/resources/db/seed/reviewed_resources.sql（INSERT IGNORE，可重跑幂等）
  - data/reports/resource_etl_report.json（来源版本、输入摘要、选入/排除数量与原因）

约定（与 ReviewedResourceSeedValidatorTest 一致）：
  - 每行一条 VALUES 元组，独占一行；字符串内不出现换行；单引号按 MySQL 规则 '' 转义。
  - 媒体无再分发许可：media_url 一律 NULL，media_state=NONE，只保留署名。
  - 全确定性：无随机、按来源 ID 排序，重跑输出逐字节一致。
"""
import argparse
import csv
import hashlib
import json
import re
import sys
from datetime import date

MEAL_SOURCE_NAME = "foodcom-recipes-and-reviews-v2"
MEAL_SOURCE_VERSION = "v2"
EXERCISE_SOURCE_NAME = "gym-visual-exercises-dataset"
EXERCISE_SOURCE_VERSION = "main-2026-08-10"
GYM_VISUAL_CREDIT = "© Gym visual — https://gymvisual.com/"

# 过敏原关键词扫描（英文原料/菜名/描述，仅做机器扫描，状态标记 REVIEWED 表示已完成扫描）
ALLERGEN_KEYWORDS = [
    ("花生", ["peanut", "groundnut"]),
    ("坚果", ["almond", "walnut", "cashew", "pecan", "hazelnut", "pistachio", "macadamia", "pine nut"]),
    ("牛奶", ["milk", "cream", "butter", "cheese", "yogurt", "yoghurt", "whey", "casein"]),
    ("鸡蛋", ["egg", "eggs"]),
    ("麸质", ["wheat", "flour", "bread", "pasta", "noodle", "spaghetti", "couscous", "barley", "rye", "gluten"]),
    ("大豆", ["soy", "soya", "tofu", "tempeh", "edamame"]),
    ("鱼", ["fish", "cod", "halibut", "salmon", "tuna", "tilapia", "trout", "snapper", "flounder", "anchovy", "sardine"]),
    ("甲壳类", ["shrimp", "prawn", "crab", "lobster", "crayfish", "shellfish", "clam", "mussel", "scallop", "squid", "oyster"]),
    ("芝麻", ["sesame"]),
]

REQUIRED_MACROS = ["calories", "fat_g", "carbohydrate_g", "protein_g"]


def mysql_escape(value: str) -> str:
    """MySQL 字符串字面量转义：单引号翻倍、反斜杠翻倍、去除换行。"""
    value = value.replace("\r", " ").replace("\n", " ")
    return value.replace("\\", "\\\\").replace("'", "''")


def json_array(values, ensure_ascii=False) -> str:
    return json.dumps(list(values), ensure_ascii=ensure_ascii)


def load_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def load_csv(path):
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def clean_text(text):
    if not text:
        return ""
    text = re.sub(r"&quot;", '"', text)
    text = re.sub(r"&amp;", "&", text)
    text = re.sub(r"&rsquo;|&lsquo;", "'", text)
    text = re.sub(r"&ldquo;|&rdquo;", '"', text)
    text = re.sub(r"&agrave;|&aacute;", "a", text)
    text = re.sub(r"&eacute;", "e", text)
    text = re.sub(r"&egrave;", "e", text)
    text = re.sub(r"&oacute;", "o", text)
    text = re.sub(r"&ograve;", "o", text)
    text = re.sub(r"&uuml;|&uuml;", "u", text)
    text = re.sub(r"&ccedil;", "c", text)
    text = re.sub(r"&[a-zA-Z]+;", "", text)
    return re.sub(r"\s+", " ", text).strip()


def scan_allergens(meal):
    """对原料/名称/描述做过敏原关键词扫描，返回 (标签列表, 状态)。"""
    haystack = " ".join([
        meal.get("name_en", ""),
        meal.get("description_en", ""),
        " ".join(json.loads(meal["ingredients_parts"]) if meal.get("ingredients_parts") else []),
    ]).lower()
    found = [label for label, keys in ALLERGEN_KEYWORDS if any(k in haystack for k in keys)]
    return found, "REVIEWED"


def parse_servings(raw):
    """份数：支持整数与小数；不支持 1/2 这类分数则返回 None。"""
    raw = raw.strip()
    try:
        value = float(raw)
        return value if value > 0 and value < 1000 else None
    except ValueError:
        return None


def build_meal_rows(meal_csv, curation, tag_map, report):
    curated_ids = {row["source_recipe_id"]: row for row in curation}
    rows = []
    report["meals"]["read"] = len(meal_csv)
    report["meals"]["curated"] = len(curated_ids)

    selected = [r for r in meal_csv if r["source_recipe_id"] in curated_ids]
    report["meals"]["curated_found_in_pool"] = len(selected)
    report["meals"]["excluded"].append(
        {"reason": "curated_but_not_in_pool", "count": len(curated_ids) - len(selected)})

    category_tags = tag_map.get("category_meal_time", {})
    category_cuisine = tag_map.get("category_cuisine", {})
    kw_health = tag_map.get("keyword_health_goal", {})
    kw_taste = tag_map.get("keyword_taste", {})
    kw_convenience = tag_map.get("keyword_convenience", {})
    kw_meal_time = tag_map.get("keyword_meal_time", {})

    excluded = report["meals"]["excluded"]
    for raw in selected:
        problems = []
        name_zh = curated_ids[raw["source_recipe_id"]]["name_zh"].strip()
        name_en = clean_text(raw["name_en"])
        if not name_zh or not name_en:
            problems.append("name_missing")
        try:
            ingredients = json.loads(raw["ingredients_parts"]) if raw.get("ingredients_parts") else []
        except ValueError:
            ingredients = []
        if not ingredients or not raw.get("instructions"):
            problems.append("content_incomplete")
        servings = parse_servings(raw.get("servings", ""))
        if servings is None:
            problems.append("servings_unparseable")
        macros = {}
        for macro in REQUIRED_MACROS:
            try:
                macros[macro] = float(raw[macro])
            except (ValueError, TypeError, KeyError):
                macros[macro] = None
        if any(v is None or v < 0 for v in macros.values()):
            problems.append("nutrition_incomplete")

        if problems:
            excluded.append({"reason": "|".join(problems), "count": 1, "recipe_id": raw["source_recipe_id"]})
            continue

        keywords = json.loads(raw.get("keywords") or "[]")
        category = raw["recipe_category"]
        meal_time = list(category_tags.get(category, []))
        meal_time += [t for kw in keywords for t in kw_meal_time.get(kw, [])]
        if not meal_time:
            meal_time = ["三餐"]
        meal_time = sorted(set(meal_time))

        cuisine = sorted(set(category_cuisine.get(category, [])))
        health_goal = sorted(set(g for kw in keywords for g in kw_health.get(kw, [])))
        taste = sorted(set(g for kw in keywords for g in kw_taste.get(kw, [])))
        convenience = sorted(set(g for kw in keywords for g in kw_convenience.get(kw, [])))

        allergens, allergen_status = scan_allergens(raw)
        description = clean_text(raw.get("description_en", ""))[:2000]
        aliases = json.loads(curated_ids[raw["source_recipe_id"]]["aliases"]) or []

        rows.append({
            "name": name_zh,
            "name_en": name_en,
            "aliases": json_array(aliases),
            "meal_time": json_array(meal_time),
            "mood": "[]",
            "scene": "[]",
            "health_goal": json_array(health_goal),
            "cuisine": json_array(cuisine),
            "taste": json_array(taste),
            "convenience": json_array(convenience),
            "description": description,
            "ingredients_json": json_array(ingredients),
            "serving_count": int(servings),
            "serving_size": "1.00",
            "serving_unit": "份",
            "calories_kcal": f"{macros['calories']:.2f}",
            "protein_g": f"{macros['protein_g']:.2f}",
            "fat_g": f"{macros['fat_g']:.2f}",
            "carbohydrate_g": f"{macros['carbohydrate_g']:.2f}",
            "nutrition_basis": "foodcom_source_value",
            "nutrition_estimated": "1",
            "allergen_json": json_array(allergens),
            "allergen_status": allergen_status,
            "review_status": "APPROVED",
            "source_name": MEAL_SOURCE_NAME,
            "source_id": raw["source_recipe_id"],
            "source_version": MEAL_SOURCE_VERSION,
            "media_url": "NULL",
            "media_status": "NONE",
            "media_credit": "NULL",
        })
    rows.sort(key=lambda r: r["source_id"])
    report["meals"]["included"] = len(rows)
    return rows


def build_exercise_rows(exercise_json, curation, report):
    by_id = {ex["id"]: ex for ex in exercise_json}
    curated = {row["source_id"]: row for row in curation}
    report["exercises"]["read"] = len(exercise_json)
    report["exercises"]["curated"] = len(curated)

    excluded = report["exercises"]["excluded"]
    rows = []
    for source_id, row in sorted(curated.items()):
        ex = by_id.get(source_id)
        if ex is None:
            excluded.append({"reason": "not_in_dataset", "count": 1, "exercise_id": source_id})
            continue
        zh_steps = ex.get("instruction_steps", {}).get("zh") or []
        zh_instructions = ex.get("instructions", {}).get("zh") or ""
        if not zh_steps or not zh_instructions:
            excluded.append({"reason": "zh_content_missing", "count": 1, "exercise_id": source_id})
            continue
        aliases = json.loads(row["aliases"]) or []
        risk_tags = json.loads(row["risk_tags"]) or []
        rows.append({
            "source_name": EXERCISE_SOURCE_NAME,
            "source_id": source_id,
            "source_version": EXERCISE_SOURCE_VERSION,
            "name": row["name_zh"],
            "name_en": ex["name"],
            "aliases": json_array(aliases),
            "category": ex.get("category", ""),
            "body_part": ex.get("body_part", ""),
            "target_muscles": json_array([ex.get("muscle_group", "")] if ex.get("muscle_group") else []),
            "secondary_muscles": json_array(ex.get("secondary_muscles") or []),
            "equipment": ex.get("equipment", ""),
            "difficulty": row["difficulty"],
            "movement_pattern": row["movement_pattern"],
            "risk_tags": json_array(risk_tags),
            "alternative_group": row["alternative_group"],
            "review_status": "APPROVED",
            "plan_ready": "1",
            "instructions_zh": clean_text(zh_instructions),
            "steps_json": json_array(zh_steps),
            "media_state": "NONE",
            "media_credit": GYM_VISUAL_CREDIT,
        })
    report["exercises"]["included"] = len(rows)
    return rows


def build_fact_rows(facts_csv, report):
    report["facts"]["read"] = len(facts_csv)
    rows = [{
        "topic": row["topic"],
        "fact_zh": row["fact_zh"],
        "scope": row["scope"],
        "source": row["source"],
        "source_version": row["source_version"],
        "ref_id": row["ref_id"],
    } for row in sorted(facts_csv, key=lambda r: r["ref_id"])]
    report["facts"]["included"] = len(rows)
    return rows


MEAL_COLUMNS = [
    "source_type", "owner_user_id", "name", "name_en", "aliases", "meal_time", "mood",
    "scene", "health_goal", "cuisine", "taste", "convenience", "description",
    "ingredients_json", "serving_count", "serving_size", "serving_unit",
    "calories_kcal", "protein_g", "fat_g", "carbohydrate_g", "nutrition_basis",
    "nutrition_estimated", "allergen_json", "allergen_status", "review_status",
    "source_name", "source_id", "source_version", "media_url", "media_status",
    "media_credit", "created_at", "updated_at",
]

EXERCISE_COLUMNS = [
    "source_name", "source_id", "source_version", "name", "name_en", "aliases",
    "category", "body_part", "target_muscles", "secondary_muscles", "equipment",
    "difficulty", "movement_pattern", "risk_tags", "alternative_group",
    "review_status", "plan_ready", "instructions_zh", "steps_json",
    "media_state", "media_credit", "created_at", "updated_at",
]

FACT_COLUMNS = [
    "topic", "fact_zh", "scope", "source", "source_version", "ref_id",
    "created_at", "updated_at",
]


def sql_value(value):
    if value == "NULL":
        return "NULL"
    if value == "NOW()":
        return "NOW()"
    if isinstance(value, str):
        return "'" + mysql_escape(value) + "'"
    return str(value)


def render_insert(table, columns, rows, fixed_prefix=None):
    """按 columns 顺序渲染 VALUES；created_at/updated_at 由脚本补 NOW()。

    fixed_prefix：meal_item 的 source_type/owner_user_id 固定值（PUBLIC/NULL）。
    row 字典的键顺序任意，渲染时按 columns 取列，保证与表头一致。
    """
    prefix = list(fixed_prefix or ())
    body_cols = [c for c in columns if c not in ("created_at", "updated_at")]
    lines = [f"INSERT IGNORE INTO `{table}` ({', '.join(columns)}) VALUES"]
    for i, row in enumerate(rows):
        values = prefix + [row[c] for c in body_cols[len(prefix):]] + ["NOW()", "NOW()"]
        suffix = ");" if i == len(rows) - 1 else "),"
        lines.append("(" + ", ".join(sql_value(v) for v in values) + suffix)
    return lines


def sha256(path):
    """输入文件摘要：重跑可验证输入未漂移，保证可复现。"""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser(description="审核资源 ETL：生成幂等 seed SQL 与导入报告")
    parser.add_argument("--meal-csv", default="data/meal/processed/healthy_recipes_1000.csv")
    parser.add_argument("--exercise-json", default="data/exercise/raw/exercises.json")
    parser.add_argument("--out-sql", default="src/main/resources/db/seed/reviewed_resources.sql")
    parser.add_argument("--out-report", default="data/reports/resource_etl_report.json")
    args = parser.parse_args()

    report = {
        "etl": "scripts/build_reviewed_resources.py",
        "generated_at": date.today().isoformat(),
        "sources": {
            "meals": {
                "name": MEAL_SOURCE_NAME,
                "version": MEAL_SOURCE_VERSION,
                "input": {"path": args.meal_csv, "sha256": sha256(args.meal_csv)},
            },
            "exercises": {
                "name": EXERCISE_SOURCE_NAME,
                "version": EXERCISE_SOURCE_VERSION,
                "input": {"path": args.exercise_json, "sha256": sha256(args.exercise_json),
                          "download": "https://cdn.jsdelivr.net/gh/hasaneyldrm/exercises-dataset@main/data/exercises.json"},
            },
        },
        "meals": {"read": 0, "curated": 0, "curated_found_in_pool": 0, "included": 0, "excluded": []},
        "exercises": {"read": 0, "curated": 0, "included": 0, "excluded": []},
        "facts": {"read": 0, "included": 0},
    }

    meal_rows = build_meal_rows(
        load_csv(args.meal_csv),
        load_csv("scripts/meal_curation/meal_name_zh.csv"),
        load_json("scripts/meal_curation/meal_tag_map.json"),
        report,
    )
    exercise_rows = build_exercise_rows(
        load_json(args.exercise_json),
        load_csv("data/exercise/curated/exercise_plan_ready.csv"),
        report,
    )
    fact_rows = build_fact_rows(load_csv("data/routine/routine_facts.csv"), report)

    sql_lines = [
        "-- 审核资源种子（33 号票，由 scripts/build_reviewed_resources.py 自动生成，请勿手改）",
        "-- 输入摘要、来源版本与选入/排除原因见 data/reports/resource_etl_report.json",
        "-- 幂等：INSERT IGNORE 依赖 uk_meal_source / uk_exercise_source / uk_routine_fact_ref 唯一键",
        "-- 媒体说明：无再分发许可，media_url 一律 NULL（稳定无图状态），仅保留署名。",
        "",
        "-- 1) 餐食（人工审核中文名 + 槽位标签，营养为 Food.com 原始估算值）",
    ]
    sql_lines += render_insert("meal_item", MEAL_COLUMNS, meal_rows,
                               fixed_prefix=("PUBLIC", "NULL"))
    sql_lines.append("")
    sql_lines.append("-- 2) 动作（gym-visual-exercises-dataset MIT 数据 + 人工审核 plan_ready 元数据）")
    sql_lines += render_insert("exercise_item", EXERCISE_COLUMNS, exercise_rows)
    sql_lines.append("")
    sql_lines.append("-- 3) 作息事实（来源见 01 号调研与 data/routine/routine_facts.csv）")
    sql_lines += render_insert("routine_fact", FACT_COLUMNS, fact_rows)

    with open(args.out_sql, "w", encoding="utf-8") as f:
        f.write("\n".join(sql_lines) + "\n")
    with open(args.out_report, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    print(f"餐食 {report['meals']['included']} 条 / 动作 {report['exercises']['included']} 条 / "
          f"事实 {report['facts']['included']} 条")
    print(f"SQL -> {args.out_sql}")
    print(f"报告 -> {args.out_report}")


if __name__ == "__main__":
    sys.exit(main())
