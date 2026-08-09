#!/usr/bin/env python3
"""从 Food.com 菜谱数据中筛选适合健康推荐的离线候选菜谱池。"""

from __future__ import annotations

import argparse
import csv
import io
import json
import re
import zipfile
from pathlib import Path
from typing import Dict, Iterable, List, Optional, TextIO


POSITIVE_TERMS = {
    "healthy": 3,
    "low-fat": 3,
    "low fat": 3,
    "low-calorie": 3,
    "low calorie": 3,
    "high-protein": 3,
    "high protein": 3,
    "vegetarian": 2,
    "vegan": 2,
    "light": 2,
    "clean eating": 2,
    "low-carb": 2,
    "low carb": 2,
    "gluten-free": 1,
    "gluten free": 1,
}

NEGATIVE_TERMS = {
    "dessert",
    "cake",
    "cookie",
    "pie",
    "brownie",
    "candy",
    "frosting",
    "ice cream",
    "cocktail",
    "drink",
    "beverage",
    "fudge",
    "deep fried",
}

OUTPUT_FIELDS = [
    "source",
    "source_recipe_id",
    "name_en",
    "description_en",
    "ingredients_quantities",
    "ingredients_parts",
    "instructions",
    "recipe_category",
    "keywords",
    "image_urls",
    "prep_time",
    "cook_time",
    "total_time",
    "servings",
    "calories",
    "fat_g",
    "saturated_fat_g",
    "cholesterol_mg",
    "sodium_mg",
    "carbohydrate_g",
    "fiber_g",
    "sugar_g",
    "protein_g",
    "nutrition_basis",
    "nutrition_estimated",
    "health_score",
    "health_reasons",
]


def parse_number(value: str) -> Optional[float]:
    """从带单位或空值的字段中读取第一个数字。"""
    if not value:
        return None
    match = re.search(r"[-+]?\d+(?:\.\d+)?", value.replace(",", ""))
    return float(match.group()) if match else None


def parse_r_vector(value: str) -> List[str]:
    """解析 Kaggle CSV 中以 R c("...") 表示的列表字段。"""
    if not value or value == "character(0)" or value == "[]":
        return []
    if value.startswith("["):
        try:
            parsed = json.loads(value)
            return [str(item).strip() for item in parsed if str(item).strip()]
        except json.JSONDecodeError:
            return [value]
    matches = re.findall(r'"((?:\\.|[^"\\])*)"', value)
    if matches:
        return [item.replace('\\"', '"').replace("\\\\", "\\").strip() for item in matches if item.strip()]
    return [value.strip()]


def as_json(values: Iterable[str]) -> str:
    return json.dumps(list(values), ensure_ascii=False)


def numeric_fields(row: Dict[str, str]) -> Dict[str, Optional[float]]:
    return {
        "calories": parse_number(row.get("Calories", "")),
        "fat_g": parse_number(row.get("FatContent", "")),
        "saturated_fat_g": parse_number(row.get("SaturatedFatContent", "")),
        "cholesterol_mg": parse_number(row.get("CholesterolContent", "")),
        "sodium_mg": parse_number(row.get("SodiumContent", "")),
        "carbohydrate_g": parse_number(row.get("CarbohydrateContent", "")),
        "fiber_g": parse_number(row.get("FiberContent", "")),
        "sugar_g": parse_number(row.get("SugarContent", "")),
        "protein_g": parse_number(row.get("ProteinContent", "")),
        "servings": parse_number(row.get("RecipeServings", "")),
    }


def score_recipe(row: Dict[str, str], numbers: Dict[str, Optional[float]]) -> tuple[int, List[str]]:
    """按可解释规则评分，不声称这是医学意义上的健康判断。"""
    text = " ".join(
        row.get(field, "")
        for field in ("Name", "Description", "RecipeCategory", "Keywords", "RecipeIngredientParts")
    ).lower()
    score = 0
    reasons: List[str] = []

    for term, points in POSITIVE_TERMS.items():
        if term in text:
            score += points
            reasons.append(f"关键词:{term}")
    for term in NEGATIVE_TERMS:
        if term in text:
            score -= 12
            reasons.append(f"排除信号:{term}")

    checks = [
        (numbers["calories"] is not None, 1, "有热量"),
        (numbers["protein_g"] is not None, 1, "有蛋白质"),
        (numbers["fat_g"] is not None, 1, "有脂肪"),
        (numbers["carbohydrate_g"] is not None, 1, "有碳水"),
        (numbers["servings"] is not None, 1, "有份量"),
    ]
    for passed, points, reason in checks:
        if passed:
            score += points
            reasons.append(reason)

    if numbers["calories"] is not None:
        if numbers["calories"] <= 650:
            score += 2
            reasons.append("热量不高于650千卡")
        elif numbers["calories"] <= 800:
            score += 1
            reasons.append("热量不高于800千卡")
    if numbers["protein_g"] is not None:
        if numbers["protein_g"] >= 20:
            score += 2
            reasons.append("蛋白质不低于20克")
        elif numbers["protein_g"] >= 15:
            score += 1
            reasons.append("蛋白质不低于15克")
    if numbers["fiber_g"] is not None and numbers["fiber_g"] >= 4:
        score += 1
        reasons.append("膳食纤维不低于4克")
    if numbers["sugar_g"] is not None and numbers["sugar_g"] <= 15:
        score += 1
        reasons.append("糖不高于15克")
    if numbers["sodium_mg"] is not None and numbers["sodium_mg"] <= 900:
        score += 1
        reasons.append("钠不高于900毫克")
    if numbers["saturated_fat_g"] is not None and numbers["saturated_fat_g"] <= 8:
        score += 1
        reasons.append("饱和脂肪不高于8克")

    if not row.get("RecipeIngredientParts") or not row.get("RecipeInstructions"):
        score -= 4
        reasons.append("食材或步骤不完整")
    return score, reasons


def open_recipe_csv(input_path: Path) -> TextIO:
    if input_path.suffix.lower() == ".zip":
        archive = zipfile.ZipFile(input_path)
        names = [name for name in archive.namelist() if name.lower().endswith("recipes.csv")]
        if not names:
            archive.close()
            raise FileNotFoundError("压缩包内找不到 recipes.csv")
        stream = archive.open(names[0], "r")
        text_stream = TextIOWrapperWithArchive(stream, archive)
        return text_stream
    return input_path.open("r", encoding="utf-8-sig", newline="")


class TextIOWrapperWithArchive(io.TextIOWrapper):
    """让 zip 内的二进制流具备文本读取接口，并在关闭时释放压缩包。"""

    def __init__(self, stream, archive: zipfile.ZipFile):
        self._archive = archive
        super().__init__(stream, encoding="utf-8-sig", newline="")

    def close(self) -> None:
        try:
            super().close()
        finally:
            self._archive.close()


def convert_row(row: Dict[str, str], score: int, reasons: List[str]) -> Dict[str, str]:
    numbers = numeric_fields(row)
    list_fields = {
        "ingredients_quantities": parse_r_vector(row.get("RecipeIngredientQuantities", "")),
        "ingredients_parts": parse_r_vector(row.get("RecipeIngredientParts", "")),
        "instructions": parse_r_vector(row.get("RecipeInstructions", "")),
        "keywords": parse_r_vector(row.get("Keywords", "")),
        "image_urls": parse_r_vector(row.get("Images", "")),
    }
    result = {
        "source": "foodcom-recipes-and-reviews-v2",
        "source_recipe_id": row.get("RecipeId", ""),
        "name_en": row.get("Name", "").strip(),
        "description_en": row.get("Description", "").strip(),
        "ingredients_quantities": as_json(list_fields["ingredients_quantities"]),
        "ingredients_parts": as_json(list_fields["ingredients_parts"]),
        "instructions": "\n".join(list_fields["instructions"]),
        "recipe_category": row.get("RecipeCategory", "").strip(),
        "keywords": as_json(list_fields["keywords"]),
        "image_urls": as_json(list_fields["image_urls"]),
        "prep_time": row.get("PrepTime", "").strip(),
        "cook_time": row.get("CookTime", "").strip(),
        "total_time": row.get("TotalTime", "").strip(),
        "nutrition_basis": "foodcom_source_value",
        "nutrition_estimated": "true",
        "health_score": str(score),
        "health_reasons": "；".join(reasons),
    }
    for field, value in numbers.items():
        result[field] = "" if value is None else f"{value:g}"
    return result


def select_recipes(input_path: Path, output_path: Path, report_path: Path, limit: int) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    candidates: List[tuple[int, int, Dict[str, str]]] = []
    seen_names = set()
    rows_read = 0

    with open_recipe_csv(input_path) as source:
        reader = csv.DictReader(source)
        for row in reader:
            rows_read += 1
            name = row.get("Name", "").strip()
            if not name or name.casefold() in seen_names:
                continue
            numbers = numeric_fields(row)
            score, reasons = score_recipe(row, numbers)
            if score < 5:
                continue
            seen_names.add(name.casefold())
            converted = convert_row(row, score, reasons)
            candidates.append((score, int(numbers["protein_g"] or 0), converted))
            if len(candidates) > limit * 8:
                candidates.sort(key=lambda item: (item[0], item[1], item[2]["source_recipe_id"]), reverse=True)
                del candidates[limit * 4 :]

    candidates.sort(key=lambda item: (item[0], item[1], item[2]["source_recipe_id"]), reverse=True)
    selected = [item[2] for item in candidates[:limit]]
    with output_path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=OUTPUT_FIELDS)
        writer.writeheader()
        writer.writerows(selected)

    report = {
        "source": "Food.com Recipes & Reviews",
        "source_version": 2,
        "license_label": "CC0: Public Domain（以数据页声明为准；图片 URL 需单独核查）",
        "rows_read": rows_read,
        "rows_selected": len(selected),
        "requested_limit": limit,
        "selection_note": "这是面向日常推荐的启发式候选筛选，不是医学意义上的健康判断。",
        "nutrition_note": "Food.com 营养值保留为估算值；正式计划计算需用 USDA 原料数据和可验证的份量换算复核。",
    }
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"已读取 {rows_read} 条，筛选出 {len(selected)} 条：{output_path}")


def main() -> None:
    parser = argparse.ArgumentParser(description="筛选 Food.com 健康菜谱候选池，不直接导入业务数据库")
    parser.add_argument("--input", required=True, type=Path, help="Food.com 压缩包或 recipes.csv 路径")
    parser.add_argument("--output", type=Path, default=Path("data/meal/processed/healthy_recipes_1000.csv"))
    parser.add_argument("--report", type=Path, default=Path("data/meal/processed/selection_report.json"))
    parser.add_argument("--limit", type=int, default=1000)
    args = parser.parse_args()
    if not args.input.exists():
        raise SystemExit(f"输入文件不存在：{args.input}")
    select_recipes(args.input, args.output, args.report, args.limit)


if __name__ == "__main__":
    main()
