#!/usr/bin/env python3
"""从审核 seed 生成 current-corpus-v2 manifest；不包含原始语料或凭证。

加固规格：seed facet 变化视为新语料版本——冻结的 current-corpus-v1 不得覆盖，
本脚本产出 current-corpus-v2（新 resourceVersion），并记录规范 facet 词表、
人工策展输入的哈希与 facetSource 溯源，使 manifest 能证明库内 facet 内容。
"""
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
seed = ROOT / "src/main/resources/db/seed/reviewed_resources.sql"
csv = ROOT / "data/meal/processed/healthy_recipes_1000.csv"
CORPUS_VERSION = "current-corpus-v2"
RESOURCE_VERSION = "reviewed-2026-08-29-v1"

rows = []
columns = None


def parse_header(line):
    """解析 INSERT 头的列名（不依赖易漂移的列下标）。"""
    inside = line[line.index("(") + 1:line.rindex(")")]
    return [c.strip().strip("`") for c in inside.split(",")]


def parse_values(line):
    body = line[line.index("(") + 1:line.rfind(")")]
    values, current, quoted = [], [], False
    i = 0
    while i < len(body):
        ch = body[i]
        if ch == "'":
            if quoted and i + 1 < len(body) and body[i + 1] == "'":
                current.append("'")
                i += 2
                continue
            quoted = not quoted
        elif ch == "," and not quoted:
            values.append("".join(current).strip())
            current = []
        else:
            current.append(ch)
        i += 1
    values.append("".join(current).strip())
    return values


content_fields = ["name", "name_en", "aliases", "meal_time", "mood",
                  "scene", "health_goal", "cuisine", "food_type", "taste", "convenience",
                  "description", "ingredients_json", "calories_kcal", "protein_g",
                  "fat_g", "carbohydrate_g", "nutrition_basis", "nutrition_estimated",
                  "allergen_json", "allergen_status", "review_status",
                  "source_name", "source_id", "source_version"]

for line in seed.read_text(encoding="utf-8").splitlines():
    if columns is None and line.startswith("INSERT INTO `meal_item`"):
        columns = parse_header(line)
        continue
    if columns is None or not line.startswith("('PUBLIC'"):
        continue
    values = parse_values(line)
    if len(values) != len(columns):
        raise SystemExit(f"seed meal row 列数变化: {len(values)} != {len(columns)}")
    fields = dict(zip(columns, values))
    canonical = {name: re.sub(r"\s+", " ", fields[name]).strip() for name in content_fields}
    canonical_json = json.dumps(canonical, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    rows.append({"sourceId": fields["source_id"],
                 "contentHash": hashlib.sha256(canonical_json.encode()).hexdigest()})
rows.sort(key=lambda row: row["sourceId"])
if len(rows) != 295 or len({row["sourceId"] for row in rows}) != 295:
    raise SystemExit(f"审核餐食数量或 sourceId 不符合基线: {len(rows)}")

etl_report = json.loads((ROOT / "data/reports/resource_etl_report.json").read_text(encoding="utf-8"))
facets = json.loads((ROOT / "data/meal/facets.json").read_text(encoding="utf-8"))
manifest = {
    "corpusVersion": CORPUS_VERSION,
    "resourceVersion": RESOURCE_VERSION,
    "generatedAt": "2026-08-29",
    "supersedes": "current-corpus-v1",
    "etl": {"script": "scripts/build_reviewed_resources.py", "report": "data/reports/resource_etl_report.json"},
    "source": {"name": "foodcom-recipes-and-reviews-v2", "version": "v2",
               "inputPath": "data/meal/processed/healthy_recipes_1000.csv",
               "inputSha256": hashlib.sha256(csv.read_bytes()).hexdigest(),
               "seedPath": "src/main/resources/db/seed/reviewed_resources.sql",
               "seedSha256": hashlib.sha256(seed.read_bytes()).hexdigest()},
    "facets": {"canonicalPath": "data/meal/facets.json", "version": facets["version"],
               "cuisineCount": len(facets["cuisines"]), "foodTypeCount": len(facets["foodTypes"]),
               "sha256": hashlib.sha256((ROOT / "data/meal/facets.json").read_bytes()).hexdigest()},
    "manualInput": {"path": "scripts/meal_curation/manual_meals.json",
                    "count": etl_report["meals"]["manual_input"]["count"],
                    "sha256": etl_report["meals"]["manual_input"]["sha256"]},
    "facetProvenance": {"note": "STABLE_KEY_DEMO=稳定来源键演示分类（ADR-0017），不是人工考证的地域事实",
                        "cuisine": etl_report["meals"]["facetSource"]["cuisine"],
                        "foodType": etl_report["meals"]["facetSource"]["food_type"]},
    "eligibility": {"sourceType": "PUBLIC", "reviewStatus": "APPROVED", "count": len(rows)},
    "embedding": {"provider": "dashscope", "model": "qwen3.7-text-embedding", "version": "v3-1024",
                  "dimension": 1024, "collection": "meal_dashscope_qwen3.7-text-embedding_1024_v3-1024",
                  "rebuildable": True},
    "contentHash": {"algorithm": "sha256", "normalization": "canonical-json-v1",
                    "fields": content_fields},
    "meals": rows,
}
out = ROOT / f"data/manifests/{CORPUS_VERSION}.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"wrote {out} ({len(rows)} meals, facetSource {manifest['facetProvenance']['cuisine']} / "
      f"{manifest['facetProvenance']['foodType']})")
