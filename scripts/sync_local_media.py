#!/usr/bin/env python3
"""同步本地展示媒体，并生成可重复导入的数据库种子与校验清单。"""

from __future__ import annotations

import argparse
import concurrent.futures
import csv
import hashlib
import json
import shutil
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
EXERCISE_SOURCE_NAME = "gym-visual-exercises-dataset"
MEAL_SOURCE_NAME = "foodcom-recipes-and-reviews-v2"
GYM_VISUAL_CREDIT = "© Gym visual — https://gymvisual.com/"
MANUAL_MEAL_IDS = {"307525", "96740", "198328"}


@dataclass(frozen=True)
class MealDownload:
    source_id: str
    source_url: str | None
    local_url: str | None
    sha256: str | None
    state: str
    detail: str | None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="同步动作 GIF/JPG 与餐食图片到本地前端目录")
    parser.add_argument("--exercise-source", type=Path,
                        help="可选：已克隆的 hasaneyldrm/exercises-dataset 目录")
    parser.add_argument("--exercise-revision", required=True,
                        help="上游仓库固定 commit，用于来源追溯")
    parser.add_argument("--skip-meals", action="store_true", help="只同步动作媒体")
    parser.add_argument("--meal-workers", type=int, default=6,
                        help="餐食图片最大并发下载数，默认 6")
    parser.add_argument("--exercise-workers", type=int, default=24,
                        help="动作媒体最大并发下载数，默认 24")
    parser.add_argument("--exercise-offset", type=int, default=0,
                        help="动作媒体下载任务起点，配合 --exercise-limit 分批恢复")
    parser.add_argument("--exercise-limit", type=int, default=0,
                        help="单次动作媒体下载任务数，0 表示完整同步")
    parser.add_argument("--meal-offset", type=int, default=0,
                        help="餐食媒体下载任务起点，配合 --meal-limit 分批恢复")
    parser.add_argument("--meal-limit", type=int, default=0,
                        help="单次餐食媒体下载任务数，0 表示完整同步")
    return parser.parse_args()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sql_literal(value: str | None) -> str:
    if value is None:
        return "NULL"
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def media_url(path: Path) -> str:
    return "/" + path.relative_to(ROOT / "frontend").as_posix()


def require_image(path: Path) -> None:
    header = path.read_bytes()[:16]
    if header.startswith(b"\xff\xd8\xff") or header.startswith(b"GIF87a") or header.startswith(b"GIF89a"):
        return
    if header.startswith(b"\x89PNG\r\n\x1a\n") or header.startswith(b"RIFF") and b"WEBP" in header:
        return
    raise ValueError(f"不是可识别的图片文件：{path}")


def read_exercises(source: Path | None) -> list[dict[str, Any]]:
    data_path = source / "data" / "exercises.json" if source else ROOT / "data" / "exercise" / "raw" / "exercises.json"
    with data_path.open(encoding="utf-8") as stream:
        exercises = json.load(stream)
    if not isinstance(exercises, list) or len(exercises) != 1324:
        raise ValueError(f"动作数据条数异常，期望 1324，实际 {len(exercises) if isinstance(exercises, list) else '非数组'}")
    return exercises


def download_exercise_media(relative_path: str, target: Path, revision: str) -> None:
    if target.is_file():
        require_image(target)
        return
    url = f"https://cdn.jsdelivr.net/gh/hasaneyldrm/exercises-dataset@{revision}/{relative_path}"
    request = urllib.request.Request(url, headers={"User-Agent": "health-agent-local-ui-media/1.0"})
    last_error: Exception | None = None
    for attempt in range(1, 5):
        try:
            with urllib.request.urlopen(request, timeout=40) as response:
                payload = response.read()
            target.write_bytes(payload)
            try:
                require_image(target)
            except ValueError:
                target.unlink(missing_ok=True)
                raise
            return
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, ValueError) as error:
            last_error = error
            target.unlink(missing_ok=True)
            if attempt < 4:
                time.sleep(attempt)
    raise RuntimeError(f"下载重试耗尽：{url}") from last_error


def sync_exercise_media(exercises: list[dict[str, Any]], source: Path | None,
                        revision: str, workers: int, offset: int = 0, limit: int = 0) -> list[dict[str, Any]] | None:
    destination = ROOT / "frontend" / "assets" / "media" / "exercises"
    image_root = destination / "images"
    video_root = destination / "videos"
    image_root.mkdir(parents=True, exist_ok=True)
    video_root.mkdir(parents=True, exist_ok=True)

    pending: list[tuple[Path, Path, str]] = []
    for exercise in exercises:
        image_relative = str(exercise["image"])
        gif_relative = str(exercise["gif_url"])
        source_image = source / image_relative if source else None
        source_gif = source / gif_relative if source else None
        image_path = image_root / Path(image_relative).name
        gif_path = video_root / Path(gif_relative).name
        if source:
            if not source_image.is_file() or not source_gif.is_file():
                raise FileNotFoundError(f"动作 {exercise.get('id')} 缺少图片或 GIF")
            require_image(source_image)
            require_image(source_gif)
            shutil.copy2(source_image, image_path)
            shutil.copy2(source_gif, gif_path)
        else:
            pending.append((image_path, Path(image_relative), str(exercise["id"])))
            pending.append((gif_path, Path(gif_relative), str(exercise["id"])))

    if not source:
        def download_pending(item: tuple[Path, Path, str]) -> None:
            target, relative, source_id = item
            try:
                download_exercise_media(relative.as_posix(), target, revision)
            except Exception as error:
                raise RuntimeError(f"动作 {source_id} 媒体下载失败：{relative}") from error

        if limit > 0:
            selected = pending[offset:offset + limit]
            with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, workers)) as executor:
                for completed, _ in enumerate(executor.map(download_pending, selected), start=1):
                    if completed % 100 == 0 or completed == len(selected):
                        print(f"动作媒体分批下载进度：{offset + completed} / {len(pending)}", flush=True)
            return None
        with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, workers)) as executor:
            for completed, _ in enumerate(executor.map(download_pending, pending), start=1):
                if completed % 100 == 0 or completed == len(pending):
                    print(f"动作媒体下载进度：{completed} / {len(pending)}", flush=True)

    rows: list[dict[str, Any]] = []
    for exercise in exercises:
        image_path = image_root / Path(str(exercise["image"])).name
        gif_path = video_root / Path(str(exercise["gif_url"])).name
        rows.append({
            "id": str(exercise["id"]),
            "name": str(exercise["name"]),
            "image": media_url(image_path),
            "gif": media_url(gif_path),
            "image_sha256": sha256(image_path),
            "gif_sha256": sha256(gif_path),
            "attribution": str(exercise.get("attribution") or GYM_VISUAL_CREDIT),
        })

    if len(rows) != 1324 or len({row["image"] for row in rows}) != 1324 or len({row["gif"] for row in rows}) != 1324:
        raise ValueError("动作媒体路径不完整或存在重复")
    return rows


def meal_candidates() -> dict[str, list[str]]:
    curated_path = ROOT / "scripts" / "meal_curation" / "meal_name_zh.csv"
    pool_path = ROOT / "data" / "meal" / "processed" / "healthy_recipes_1000.csv"
    curated_ids = set(MANUAL_MEAL_IDS)
    with curated_path.open(encoding="utf-8", newline="") as stream:
        curated_ids.update(row["source_recipe_id"] for row in csv.DictReader(stream))

    urls_by_id: dict[str, list[str]] = {source_id: [] for source_id in curated_ids}
    with pool_path.open(encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream):
            source_id = row["source_recipe_id"]
            if source_id not in urls_by_id:
                continue
            try:
                urls = json.loads(row.get("image_urls") or "[]")
            except json.JSONDecodeError:
                urls = []
            urls_by_id[source_id] = [url for url in urls if isinstance(url, str) and url.startswith("https://")]
    if len(urls_by_id) != 295:
        raise ValueError(f"餐食审核 ID 数量异常，期望 295，实际 {len(urls_by_id)}")
    return urls_by_id


def extension_from_content_type(content_type: str) -> str:
    normalized = content_type.lower().split(";", 1)[0].strip()
    return {
        "image/jpeg": ".jpg",
        "image/png": ".png",
        "image/gif": ".gif",
        "image/webp": ".webp",
    }.get(normalized, ".jpg")


def download_meal(source_id: str, source_urls: list[str], target: Path) -> MealDownload:
    if not source_urls:
        return MealDownload(source_id, None, None, None, "MISSING_SOURCE", "原始数据无图片 URL")
    existing = next(target.glob(f"{source_id}.*"), None)
    if existing and existing.is_file():
        require_image(existing)
        return MealDownload(source_id, source_urls[0], media_url(existing), sha256(existing), "LICENSED", None)
    last_error: Exception | None = None
    for source_url in source_urls:
        request = urllib.request.Request(source_url, headers={"User-Agent": "health-agent-local-ui-media/1.0"})
        try:
            with urllib.request.urlopen(request, timeout=25) as response:
                content_type = response.headers.get_content_type()
                payload = response.read()
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as error:
            last_error = error
            continue
        extension = extension_from_content_type(content_type)
        path = target / f"{source_id}{extension}"
        path.write_bytes(payload)
        try:
            require_image(path)
        except ValueError as error:
            path.unlink(missing_ok=True)
            last_error = error
            continue
        return MealDownload(source_id, source_url, media_url(path), sha256(path), "LICENSED", None)
    return MealDownload(source_id, source_urls[0], None, None, "DOWNLOAD_FAILED", str(last_error))


def sync_meal_media(workers: int, offset: int = 0, limit: int = 0) -> list[MealDownload] | None:
    target = ROOT / "frontend" / "assets" / "media" / "meals"
    target.mkdir(parents=True, exist_ok=True)
    candidates = meal_candidates()
    tasks = sorted(candidates.items(), key=lambda item: int(item[0]))
    if limit > 0:
        selected = tasks[offset:offset + limit]
        with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, workers)) as executor:
            for completed, _ in enumerate(executor.map(lambda item: download_meal(item[0], item[1], target), selected), start=1):
                if completed % 50 == 0 or completed == len(selected):
                    print(f"餐食媒体分批下载进度：{offset + completed} / {len(tasks)}", flush=True)
        return None
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, workers)) as executor:
        futures = [executor.submit(download_meal, source_id, urls, target) for source_id, urls in tasks]
        rows = [future.result() for future in futures]
    return sorted(rows, key=lambda item: int(item.source_id))


def exercise_insert_row(exercise: dict[str, Any], media: dict[str, Any], revision: str) -> str:
    zh_instructions = str((exercise.get("instructions") or {}).get("zh") or "")
    zh_steps = (exercise.get("instruction_steps") or {}).get("zh") or []
    primary_muscle = str(exercise.get("muscle_group") or "")
    values = [
        EXERCISE_SOURCE_NAME,
        str(exercise["id"]),
        revision,
        str(exercise["name"]),
        str(exercise["name"]),
        json.dumps([], ensure_ascii=False),
        str(exercise.get("category") or ""),
        str(exercise.get("body_part") or ""),
        json.dumps([primary_muscle] if primary_muscle else [], ensure_ascii=False),
        json.dumps(exercise.get("secondary_muscles") or [], ensure_ascii=False),
        str(exercise.get("equipment") or ""),
        "未评估",
        "未评估",
        json.dumps([], ensure_ascii=False),
        None,
        "PENDING",
        "0",
        zh_instructions,
        json.dumps(zh_steps, ensure_ascii=False),
        media["image"],
        media["gif"],
        "LICENSED",
        media["attribution"],
    ]
    return "(" + ", ".join(sql_literal(value) if value != "0" else "0" for value in values) + ", NOW(), NOW())"


def write_seed(exercises: list[dict[str, Any]], exercise_media: list[dict[str, Any]], meals: list[MealDownload], revision: str) -> None:
    media_by_id = {row["id"]: row for row in exercise_media}
    columns = """source_name, source_id, source_version, name, name_en, aliases, category, body_part,
target_muscles, secondary_muscles, equipment, difficulty, movement_pattern, risk_tags, alternative_group,
review_status, plan_ready, instructions_zh, steps_json, thumbnail_url, media_url, media_state, media_credit,
created_at, updated_at"""
    rows = [exercise_insert_row(exercise, media_by_id[str(exercise["id"])], revision) for exercise in exercises]
    sql = [
        "-- 仅供本地展示：完整动作资料库（1324 条）与餐食本地媒体映射。",
        "-- 媒体授权由项目所有者确认；自动周计划仍只使用既有 plan_ready=1 审核动作。",
        f"INSERT INTO exercise_item ({columns}) VALUES",
        ",\n".join(rows),
        "ON DUPLICATE KEY UPDATE",
        "  source_version = VALUES(source_version),",
        "  thumbnail_url = VALUES(thumbnail_url),",
        "  media_url = VALUES(media_url),",
        "  media_state = VALUES(media_state),",
        "  media_credit = VALUES(media_credit),",
        "  updated_at = NOW();",
        "",
    ]
    for meal in meals:
        if meal.state != "LICENSED" or not meal.local_url:
            continue
        sql.extend([
            "UPDATE meal_item",
            f"SET media_url = {sql_literal(meal.local_url)}, media_status = 'LICENSED',",
            f"    media_credit = {sql_literal('Food.com recipe image — local educational/non-commercial use')}, updated_at = NOW()",
            f"WHERE source_name = {sql_literal(MEAL_SOURCE_NAME)} AND source_id = {sql_literal(meal.source_id)} AND review_status = 'APPROVED';",
            "",
        ])
    target = ROOT / "src" / "main" / "resources" / "db" / "seed" / "local_media_catalog.sql"
    target.write_text("\n".join(sql), encoding="utf-8")


def write_manifest(exercise_media: list[dict[str, Any]], meals: list[MealDownload], revision: str) -> None:
    target = ROOT / "frontend" / "assets" / "media" / "MEDIA_MANIFEST.json"
    payload = {
        "purpose": "本地面试展示与非商业教育测试",
        "exercise_source": {
            "repository": "https://github.com/hasaneyldrm/exercises-dataset",
            "revision": revision,
            "count": len(exercise_media),
            "attribution": GYM_VISUAL_CREDIT,
            "items": exercise_media,
        },
        "meal_source": {
            "name": MEAL_SOURCE_NAME,
            "count": len(meals),
            "items": [item.__dict__ for item in meals],
        },
    }
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    source = args.exercise_source.resolve() if args.exercise_source else None
    exercises = read_exercises(source)
    media = sync_exercise_media(exercises, source, args.exercise_revision, args.exercise_workers,
                                args.exercise_offset, args.exercise_limit)
    if media is None:
        print("动作媒体分批同步完成，尚未生成数据库种子", flush=True)
        return 0
    meals = [] if args.skip_meals else sync_meal_media(args.meal_workers, args.meal_offset, args.meal_limit)
    if meals is None:
        print("餐食媒体分批同步完成，尚未生成数据库种子", flush=True)
        return 0
    write_seed(exercises, media, meals, args.exercise_revision)
    write_manifest(media, meals, args.exercise_revision)
    meal_summary = "未同步" if args.skip_meals else f"成功 {sum(item.state == 'LICENSED' for item in meals)} / {len(meals)}"
    print(f"动作媒体：{len(media)} 张 JPG + {len(media)} 个 GIF；餐食媒体：{meal_summary}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"同步失败：{error}", file=sys.stderr)
        raise SystemExit(1)
