#!/usr/bin/env python3
"""动作权威源导入工具：默认 dry-run，apply 才会执行 MySQL 写入。"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from collections import Counter
from pathlib import Path
from typing import Any


QUALIFICATION_VERSION = "exercise-qualification-v1"
SOURCE_NAME = "gym-visual-exercises-dataset"
DEFAULT_SOURCE = Path("/Users/pp/Downloads/exercises.json")

BODY_PART_ZH = {
    "back": "背", "cardio": "全身", "chest": "胸", "lower arms": "手臂",
    "lower legs": "腿", "neck": "颈部", "shoulders": "肩", "upper arms": "手臂",
    "upper legs": "腿", "waist": "核心",
}
EQUIPMENT_ZH = {
    "assisted": "器械", "band": "弹力带", "barbell": "杠铃", "body weight": "徒手",
    "bosu ball": "器械", "cable": "器械", "dumbbell": "哑铃", "elliptical machine": "器械",
    "ez barbell": "杠铃", "hammer": "器械", "kettlebell": "壶铃", "leverage machine": "器械",
    "medicine ball": "器械", "olympic barbell": "杠铃", "resistance band": "弹力带",
    "roller": "器械", "rope": "器械", "skierg machine": "器械", "sled machine": "器械",
    "smith machine": "器械", "stability ball": "器械", "stationary bike": "器械",
    "stepmill machine": "器械", "tire": "器械", "trap bar": "杠铃",
    "upper body ergometer": "器械", "weighted": "器械", "wheel roller": "器械",
}
MUSCLE_ZH = {
    "abductors": "腿", "abs": "核心", "adductors": "腿", "cardiovascular system": "全身",
    "delts": "肩", "levator scapulae": "肩", "pectorals": "胸", "quads": "腿", "serratus anterior": "胸",
    "spine": "核心",
    "abdominals": "核心", "ankle stabilizers": "腿", "ankles": "腿", "chest": "胸",
    "triceps": "手臂", "biceps": "手臂", "brachialis": "手臂", "forearms": "手臂",
    "grip muscles": "手臂", "hands": "手臂", "shoulders": "肩", "deltoids": "肩",
    "traps": "背", "trapezius": "背", "upper back": "背", "rhomboids": "背",
    "latissimus dorsi": "背", "lats": "背", "quadriceps": "腿", "hamstrings": "腿",
    "calves": "腿", "soleus": "腿", "feet": "腿", "shins": "腿", "groin": "腿",
    "inner thighs": "腿", "core": "核心", "lower abs": "核心", "obliques": "核心",
    "hip flexors": "核心", "lower back": "核心", "glutes": "臀", "rear deltoids": "肩",
    "rotator cuff": "肩", "sternocleidomastoid": "颈部", "upper chest": "胸",
    "wrist extensors": "手臂", "wrist flexors": "手臂", "wrists": "手臂",
}


def args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="导入完整动作目录并生成差异审计报告")
    parser.add_argument("--input", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--mode", choices=("dry-run", "apply"), default="dry-run")
    parser.add_argument("--report", type=Path, default=Path("data/reports/exercise_import_report.json"))
    parser.add_argument("--sql", type=Path, default=Path("data/reports/exercise_import.sql"))
    parser.add_argument("--qualification-manifest", type=Path,
                        help="可核验的审核资格清单 JSON；未提供时不新增 PLAN_READY 资格")
    parser.add_argument("--mysql-database", default=os.getenv("DIET_DB_NAME", "diet_db"))
    parser.add_argument("--mysql-host", default=os.getenv("DIET_DB_HOST", "127.0.0.1"))
    parser.add_argument("--mysql-port", default=os.getenv("DIET_DB_PORT", "3306"))
    parser.add_argument("--mysql-user", default=os.getenv("DIET_DB_USER", "root"))
    return parser.parse_args()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def text(value: Any) -> str:
    return "" if value is None else str(value).strip()


def compact_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def source_record_hash(item: dict[str, Any]) -> str:
    source_fields = {key: item.get(key) for key in (
        "id", "name", "category", "body_part", "equipment", "target", "muscle_group",
        "secondary_muscles", "instructions", "instruction_steps", "image", "gif_url",
    )}
    return hashlib.sha256(compact_json(source_fields).encode("utf-8")).hexdigest()


def zh_part(value: str) -> str:
    return BODY_PART_ZH.get(value, MUSCLE_ZH.get(value, ""))


def derive(item: dict[str, Any], qualification_manifest: dict[str, Any] | None = None) -> dict[str, Any]:
    category = text(item.get("category"))
    body_part = text(item.get("body_part"))
    equipment = text(item.get("equipment"))
    target = text(item.get("target"))
    muscle_group = text(item.get("muscle_group"))
    secondary = item.get("secondary_muscles") or []
    instructions = item.get("instructions") or {}
    steps = (item.get("instruction_steps") or {}).get("zh") or []
    unmapped = {
        "category": [] if zh_part(category) else ([category] if category else []),
        "body_part": [] if zh_part(body_part) else ([body_part] if body_part else []),
        "equipment": [] if equipment in EQUIPMENT_ZH else ([equipment] if equipment else []),
        "target": [] if zh_part(target) else ([target] if target else []),
        "muscle_group": [] if zh_part(muscle_group) else ([muscle_group] if muscle_group else []),
        "secondary_muscles": sorted({value for value in secondary if value and not zh_part(value)}),
    }
    complete = all(text(item.get(key)) for key in ("id", "name", "category", "body_part", "equipment", "target", "muscle_group"))
    visible = bool(complete and isinstance(instructions, dict) and text(instructions.get("zh")) and steps)
    recommendable = bool(visible and not any(unmapped.values()))
    manifest_entry = (qualification_manifest or {}).get(text(item.get("id")), {})
    plan_ready = bool(manifest_entry.get("planReady", False))
    name = text(item.get("name")).lower()
    if any(term in name for term in ("stretch", "mobility", "warm-up", "warm up", "assisted", "wall", "rehabilitation")):
        difficulty = "入门"
    elif any(term in name for term in ("jump", "plyometric", "burpee", "handstand", "muscle-up", "one-arm")):
        difficulty = "挑战"
    else:
        difficulty = "进阶"
    movement_pattern = "有氧" if category == "cardio" or body_part == "cardio" else "力量"
    risk_tags = []
    if body_part in {"lower legs", "upper legs"}:
        risk_tags.append("下肢冲击")
    if target in {"abs", "obliques"} or muscle_group in {"lower back", "hip flexors"}:
        risk_tags.append("核心与腰部控制")
    if equipment not in {"body weight", "band", "resistance band"}:
        risk_tags.append("器材安全")
    return {
        "visible": visible,
        "recommendable": recommendable,
        "plan_ready": plan_ready,
        "review_status": "APPROVED" if manifest_entry.get("reviewStatus") == "APPROVED" else "PENDING",
        "difficulty": difficulty,
        "movement_pattern": movement_pattern,
        "risk_tags": risk_tags,
        "unmapped": {key: value for key, value in unmapped.items() if value},
        "instructions_zh_status": "SOURCE_TRANSLATION",
        "source_hash": source_record_hash(item),
    }


def validate(items: Any) -> tuple[list[dict[str, Any]], list[str]]:
    errors: list[str] = []
    if not isinstance(items, list):
        return [], ["源文件根节点必须是数组"]
    if len(items) != 1324:
        errors.append(f"记录数应为 1324，实际为 {len(items)}")
    ids = [text(item.get("id")) for item in items if isinstance(item, dict)]
    if len(ids) != len(set(ids)):
        errors.append("source_id 存在重复")
    required = ("name", "category", "body_part", "equipment", "target", "muscle_group", "secondary_muscles", "instructions")
    for index, item in enumerate(items):
        if not isinstance(item, dict):
            errors.append(f"第 {index + 1} 条不是对象")
            continue
        for key in required:
            if key not in item or item[key] in (None, ""):
                errors.append(f"source_id={item.get('id')} 缺失 {key}")
        if not text((item.get("instructions") or {}).get("zh")):
            errors.append(f"source_id={item.get('id')} 缺失 instructions.zh")
    return [item for item in items if isinstance(item, dict)], errors


def sql(value: Any) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, (dict, list)):
        value = compact_json(value)
    escaped = str(value).replace("\\", "\\\\").replace("'", "''")
    return "'" + escaped + "'"


def rows_sql(items: list[dict[str, Any]], source_version: str,
             qualification_manifest: dict[str, Any] | None = None) -> str:
    columns = (
        "source_name, source_id, source_version, source_hash, name, name_en, aliases, category, body_part, "
        "source_category, source_body_part, source_equipment, source_target, source_muscle_group, "
        "target_muscles, secondary_muscles, source_secondary_muscles, equipment, difficulty, movement_pattern, "
        "risk_tags, review_status, plan_ready, qualification_version, qualification_visible, "
        "qualification_recommendable, qualification_plan_ready, qualification_report_json, instructions_zh, "
        "steps_json, source_instructions_json, source_media_image, source_media_gif, instructions_zh_status, "
        "media_state, media_credit, created_at, updated_at"
    )
    values = []
    for item in sorted(items, key=lambda value: text(value.get("id"))):
        derived = derive(item, qualification_manifest)
        secondary = item.get("secondary_muscles") or []
        values.append("(" + ", ".join((
            sql(SOURCE_NAME), sql(text(item.get("id"))), sql(source_version), sql(derived["source_hash"]),
            sql(text(item.get("name"))), sql(text(item.get("name"))), sql([]), sql(text(item.get("category"))),
            sql(text(item.get("body_part"))), sql(text(item.get("category"))), sql(text(item.get("body_part"))),
            sql(text(item.get("equipment"))), sql(text(item.get("target"))), sql(text(item.get("muscle_group"))),
            sql([text(item.get("muscle_group"))] if text(item.get("muscle_group")) else []), sql(secondary), sql(secondary),
            sql(text(item.get("equipment"))), sql(derived["difficulty"]), sql(derived["movement_pattern"]),
            sql(derived["risk_tags"]), sql(derived["review_status"]),
            "1" if derived["plan_ready"] else "0", sql(QUALIFICATION_VERSION),
            "1" if derived["visible"] else "0", "1" if derived["recommendable"] else "0",
            "1" if derived["plan_ready"] else "0", sql(derived),
            sql((item.get("instructions") or {}).get("zh")), sql((item.get("instruction_steps") or {}).get("zh") or []),
            sql(item.get("instructions") or {}), sql(item.get("image")), sql(item.get("gif_url")),
            sql(derived["instructions_zh_status"]), sql("NONE"), sql(item.get("attribution")), "NOW()", "NOW()"
        )) + ")")
    qualification_update = (
        "review_status=VALUES(review_status), plan_ready=VALUES(plan_ready), "
        "qualification_visible=VALUES(qualification_visible), "
        "qualification_recommendable=VALUES(qualification_recommendable), "
        "qualification_plan_ready=VALUES(qualification_plan_ready), "
    ) if qualification_manifest is not None else (
        "review_status=review_status, plan_ready=plan_ready, "
        "qualification_visible=qualification_visible, qualification_recommendable=qualification_recommendable, "
        "qualification_plan_ready=qualification_plan_ready, "
    )
    return (
        f"INSERT INTO exercise_item ({columns}) VALUES\n" + ",\n".join(values) + "\n"
        "ON DUPLICATE KEY UPDATE source_version=VALUES(source_version), source_hash=VALUES(source_hash), "
        "name=COALESCE(NULLIF(VALUES(name), ''), name), name_en=COALESCE(NULLIF(VALUES(name_en), ''), name_en), "
        "category=COALESCE(NULLIF(VALUES(category), ''), category), body_part=COALESCE(NULLIF(VALUES(body_part), ''), body_part), "
        "source_category=COALESCE(NULLIF(VALUES(source_category), ''), source_category), "
        "source_body_part=COALESCE(NULLIF(VALUES(source_body_part), ''), source_body_part), "
        "source_equipment=COALESCE(NULLIF(VALUES(source_equipment), ''), source_equipment), "
        "source_target=COALESCE(NULLIF(VALUES(source_target), ''), source_target), "
        "source_muscle_group=COALESCE(NULLIF(VALUES(source_muscle_group), ''), source_muscle_group), "
        "source_secondary_muscles=CASE WHEN JSON_LENGTH(VALUES(source_secondary_muscles)) > 0 "
        "THEN VALUES(source_secondary_muscles) ELSE source_secondary_muscles END, "
        "source_instructions_json=CASE WHEN JSON_LENGTH(VALUES(source_instructions_json)) > 0 "
        "THEN VALUES(source_instructions_json) ELSE source_instructions_json END, "
        "source_media_image=COALESCE(NULLIF(VALUES(source_media_image), ''), source_media_image), "
        "source_media_gif=COALESCE(NULLIF(VALUES(source_media_gif), ''), source_media_gif), "
        "target_muscles=CASE WHEN JSON_LENGTH(VALUES(target_muscles)) > 0 THEN VALUES(target_muscles) ELSE target_muscles END, "
        "secondary_muscles=CASE WHEN JSON_LENGTH(VALUES(secondary_muscles)) > 0 THEN VALUES(secondary_muscles) ELSE secondary_muscles END, "
        "equipment=COALESCE(NULLIF(VALUES(equipment), ''), equipment), "
        "difficulty=VALUES(difficulty), movement_pattern=VALUES(movement_pattern), risk_tags=VALUES(risk_tags), "
        + qualification_update
        + "qualification_version=VALUES(qualification_version), "
        "qualification_report_json=VALUES(qualification_report_json), "
        "instructions_zh=COALESCE(NULLIF(VALUES(instructions_zh), ''), instructions_zh), "
        "steps_json=CASE WHEN JSON_LENGTH(VALUES(steps_json)) > 0 THEN VALUES(steps_json) ELSE steps_json END, "
        "instructions_zh_status=VALUES(instructions_zh_status), updated_at=NOW();\n"
    )


def mysql_command(options: argparse.Namespace) -> list[str]:
    return ["mysql", "--abort-source-on-error", "-h", options.mysql_host, "-P", str(options.mysql_port),
            "-u", options.mysql_user, options.mysql_database]


def mysql_environment() -> dict[str, str]:
    environment = os.environ.copy()
    password = os.getenv("DIET_DB_PASSWORD")
    if password:
        environment["MYSQL_PWD"] = password
    return environment


def existing_source_ids(options: argparse.Namespace) -> set[str]:
    query = "SELECT source_id FROM exercise_item WHERE source_name = " + sql(SOURCE_NAME) + ";"
    result = subprocess.run(mysql_command(options), input=query, text=True,
                            env=mysql_environment(), capture_output=True, check=False)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "无法读取动作源键")
    return {line.strip() for line in result.stdout.splitlines() if line.strip()}


def main() -> int:
    options = args()
    source = options.input.expanduser().resolve()
    if not source.is_file():
        print(f"源文件不存在：{source}", file=sys.stderr)
        return 2
    items, errors = validate(json.loads(source.read_text(encoding="utf-8")))
    qualification_manifest: dict[str, Any] | None = None
    if options.qualification_manifest:
        manifest_path = options.qualification_manifest.expanduser().resolve()
        if not manifest_path.is_file():
            print(f"资格清单不存在：{manifest_path}", file=sys.stderr)
            return 2
        manifest_value = json.loads(manifest_path.read_text(encoding="utf-8"))
        qualification_manifest = manifest_value.get("items", manifest_value) if isinstance(manifest_value, dict) else manifest_value
        if not isinstance(qualification_manifest, dict):
            print("资格清单必须是 source_id 到资格对象的 JSON 映射", file=sys.stderr)
            return 2
    derived = [derive(item, qualification_manifest) for item in items]
    report = {
        "source": {"path": str(source), "sha256": sha256(source), "sourceName": SOURCE_NAME, "count": len(items)},
        "mode": options.mode,
        "requiredFieldCompleteness": {key: sum(bool(text(item.get(key))) for item in items) for key in (
            "name", "category", "body_part", "equipment", "target", "muscle_group", "secondary_muscles", "instructions")},
        "instructionsZhCount": sum(bool(text((item.get("instructions") or {}).get("zh"))) for item in items),
        "media": {"imageCount": sum(bool(text(item.get("image"))) for item in items), "gifCount": sum(bool(text(item.get("gif_url"))) for item in items),
                  "state": "SOURCE_REFERENCE_ONLY"},
        "rawOptionalFields": {key: sum(key in item for item in items) for key in ("difficulty", "movement_pattern", "risk_tags", "plan_ready")},
        "qualificationVersion": QUALIFICATION_VERSION,
        "qualificationManifest": str(options.qualification_manifest) if options.qualification_manifest else None,
        "qualification": {"VISIBLE": sum(item["visible"] for item in derived), "RECOMMENDABLE": sum(item["recommendable"] for item in derived), "PLAN_READY": sum(item["plan_ready"] for item in derived)},
        "unmappedValues": {field: sorted({value for result in derived for value in result["unmapped"].get(field, [])}) for field in ("category", "body_part", "equipment", "target", "muscle_group", "secondary_muscles")},
        "missingRecords": [],
        "errors": errors,
    }
    report["missingRecords"] = {"status": "NOT_QUERIED_IN_DRY_RUN", "detail": "apply 前不会读取数据库，避免 dry-run 产生写入或依赖数据库"}
    sql_text = rows_sql(items, report["source"]["sha256"][:16], qualification_manifest)
    options.report.parent.mkdir(parents=True, exist_ok=True)
    options.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    options.sql.parent.mkdir(parents=True, exist_ok=True)
    options.sql.write_text(sql_text, encoding="utf-8")
    if errors:
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 1
    if options.mode == "apply":
        try:
            before = existing_source_ids(options)
            transaction_sql = "START TRANSACTION;\n" + sql_text + "COMMIT;\n"
            result = subprocess.run(mysql_command(options), input=transaction_sql, text=True,
                                    env=mysql_environment(), capture_output=True)
        except RuntimeError as error:
            print(str(error), file=sys.stderr)
            return 2
        if result.returncode != 0:
            print(result.stderr, file=sys.stderr)
            return result.returncode
        after = existing_source_ids(options)
        source_ids = {text(item.get("id")) for item in items}
        report["missingRecords"] = {
            "status": "APPLIED",
            "databaseCount": len(after),
            "existingBeforeCount": len(before),
            "newRecords": sorted(after - before),
            "missingSourceRecords": sorted(source_ids - after),
        }
        options.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))
        print(f"已应用动作导入：{options.mysql_database}")
    else:
        print(json.dumps(report, ensure_ascii=False, indent=2))
        print("dry-run：未写入数据库")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
