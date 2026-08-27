#!/usr/bin/env python3
"""从审核 seed 生成 current-corpus-v1 manifest；不包含原始语料或凭证。"""
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
seed = ROOT / "src/main/resources/db/seed/reviewed_resources.sql"
csv = ROOT / "data/meal/processed/healthy_recipes_1000.csv"
rows = []
columns = ["source_type", "owner_user_id", "name", "name_en", "aliases", "meal_time", "mood",
           "scene", "health_goal", "cuisine", "taste", "convenience", "description", "ingredients_json",
           "serving_count", "serving_size", "serving_unit", "calories_kcal", "protein_g", "fat_g",
           "carbohydrate_g", "nutrition_basis", "nutrition_estimated", "allergen_json", "allergen_status",
           "review_status", "source_name", "source_id", "source_version", "media_url", "media_status",
           "media_credit", "created_at", "updated_at"]

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

for line in seed.read_text(encoding="utf-8").splitlines():
    if "'foodcom-recipes-and-reviews-v2', '" not in line or "'v2'" not in line:
        continue
    match = re.search(r"'foodcom-recipes-and-reviews-v2', '([^']+)', 'v2'", line)
    if not match:
        continue
    values = parse_values(line)
    if len(values) != len(columns):
        raise SystemExit(f"seed meal row 列数变化: {len(values)}")
    fields = dict(zip(columns, values))
    canonical = {name: re.sub(r"\s+", " ", fields[name]).strip() for name in (
        "name", "name_en", "aliases", "meal_time", "mood", "scene", "health_goal", "cuisine",
        "taste", "convenience", "description", "ingredients_json", "calories_kcal", "protein_g",
        "fat_g", "carbohydrate_g", "nutrition_basis", "nutrition_estimated", "allergen_json",
        "allergen_status", "review_status", "source_name", "source_id", "source_version")}
    canonical_json = json.dumps(canonical, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    rows.append({"sourceId": match.group(1), "contentHash": hashlib.sha256(canonical_json.encode()).hexdigest()})
rows.sort(key=lambda row: row["sourceId"])
if len(rows) != 295 or len({row["sourceId"] for row in rows}) != 295:
    raise SystemExit(f"审核餐食数量或 sourceId 不符合基线: {len(rows)}")
manifest = {
    "corpusVersion": "current-corpus-v1",
    "resourceVersion": "reviewed-2026-08-10-v1",
    "generatedAt": "2026-08-26",
    "etl": {"script": "scripts/build_reviewed_resources.py", "report": "data/reports/resource_etl_report.json"},
    "source": {"name": "foodcom-recipes-and-reviews-v2", "version": "v2",
               "inputPath": "data/meal/processed/healthy_recipes_1000.csv",
               "inputSha256": hashlib.sha256(csv.read_bytes()).hexdigest(),
               "seedPath": "src/main/resources/db/seed/reviewed_resources.sql",
               "seedSha256": hashlib.sha256(seed.read_bytes()).hexdigest()},
    "eligibility": {"sourceType": "PUBLIC", "reviewStatus": "APPROVED", "count": len(rows)},
    "embedding": {"provider": "dashscope", "model": "qwen3.7-text-embedding", "version": "v3-1024",
                   "dimension": 1024, "collection": "meal_dashscope_qwen3.7-text-embedding_1024_v3-1024",
                   "rebuildable": True},
    "contentHash": {"algorithm": "sha256", "normalization": "canonical-json-v1",
                     "fields": ["name", "name_en", "aliases", "meal_time", "mood", "scene", "health_goal",
                                "cuisine", "taste", "convenience", "description", "ingredients_json",
                                "calories_kcal", "protein_g", "fat_g", "carbohydrate_g", "nutrition_basis",
                                "nutrition_estimated", "allergen_json", "allergen_status", "review_status",
                                "source_name", "source_id", "source_version"]},
    "meals": rows,
}
out = ROOT / "data/manifests/current-corpus-v1.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"wrote {out} ({len(rows)} meals)")
