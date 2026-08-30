#!/usr/bin/env python3
"""同步本地展示媒体，并生成可重复导入的数据库种子与校验清单。

餐食图片优先级：本地既有样本映射（MEDIA_MANIFEST 备注）> Food.com 原始 URL
（processed 池 + 原始数据包 Images 列恢复）> Wikimedia Commons 按审核英文名/中文名检索兜底。
仅本地展示媒体；正式审核种子（reviewed_resources.sql）保持无图状态不变。
"""

from __future__ import annotations

import argparse
import concurrent.futures
import csv
import hashlib
import io
import json
import re
import shutil
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
EXERCISE_SOURCE_NAME = "gym-visual-exercises-dataset"
MEAL_SOURCE_NAME = "foodcom-recipes-and-reviews-v2"
GYM_VISUAL_CREDIT = "© Gym visual — https://gymvisual.com/"
MANUAL_MEAL_IDS = {"307525", "96740", "198328"}
COMMONS_API = "https://commons.wikimedia.org/w/api.php"
COMMONS_UA = "health-agent-local-ui-media/1.0"
FOOD_MEAL_CREDIT = "Food.com recipe image — local educational/non-commercial use"
WIKIMEDIA_MEAL_CREDIT = "Wikimedia Commons image — local educational/non-commercial use"
MANUAL_MEAL_CREDIT = "Unsplash License — local educational/non-commercial use"
# 本地既有样本直接映射：source_id -> (local_url, source_url)
# （来源与许可见 frontend/assets/media/reviewed/README.md）
MANUAL_LOCAL_MEALS = {
    "100332": ("/assets/media/reviewed/black-bean-lasagna.jpg",
               "https://images.unsplash.com/photo-1574894709920-11b28e7367e3"),
}
# 人工复核的 Wikimedia 检索词覆盖（2026-08-31 质量轮）：默认变体链对这些菜检索不到
# 标题匹配的位图，但以下词能稳定命中同类菜品照片（source_id -> 检索词列表）。
MANUAL_COMMONS_QUERIES = {
    "47989": ["salmon fillet"],
    "53472": ["chicken fajitas"],
    "68451": ["fish kebab"],
    "81185": ["guacamole"],
    "89922": ["nachos"],
    "95094": ["chilaquiles"],
    "104865": ["chicken vegetable stir fry"],
    "117145": ["toothfish"],
    "224467": ["tofu dish"],
    "280313": ["tomatillo chicken"],
    "290088": ["pasta salad salmon"],
    "290185": ["chickpea curry"],
    "328224": ["chettinad chicken"],
    "328616": ["sweet and sour chicken"],
    "329384": ["chicken marengo"],
    "371474": ["salmon steak"],
    "416226": ["halibut"],
    "419409": ["flounder"],
    "453099": ["tacos"],
    "465006": ["corn chowder"],
    "483396": ["toor dal"],
    # 同图去重（2026-08-31 质量轮）：双份同图菜中为第二道指定差异化词
    "160828": ["vegetable lasagna"],
    "61437": ["fettuccine alfredo"],
    "307525": ["lemon chicken rice"],
    "317745": ["lemon grilled chicken"],
    "478373": ["asparagus soup"],
    "477571": ["spinach dip"],
}
# 人工核准检索词的取图偏移（trusted-first 取第 n+1 个结果，用于兄弟菜同图去重）
MANUAL_COMMONS_RESULT_INDEX = {
    "160828": 1,
    "61437": 1,
    "478373": 1,
    "477571": 1,
}


@dataclass(frozen=True)
class MealDownload:
    source_id: str
    source_url: str | None
    local_url: str | None
    sha256: str | None
    state: str
    detail: str | None
    credit: str | None = None


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


def meal_candidates() -> dict[str, dict[str, Any]]:
    """每条审核餐食的媒体来源：Food.com 原始 URL 优先（processed 池 + 原始数据包恢复），
    无 URL 时记录 Wikimedia Commons 检索词（审核英文名优先、中文名兜底），本地样本直接映射。"""
    curated_path = ROOT / "scripts" / "meal_curation" / "meal_name_zh.csv"
    pool_path = ROOT / "data" / "meal" / "processed" / "healthy_recipes_1000.csv"
    curated_ids = set(MANUAL_MEAL_IDS)
    curated_zh: dict[str, str] = {}
    with curated_path.open(encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream):
            curated_ids.add(row["source_recipe_id"])
            curated_zh[row["source_recipe_id"]] = (row.get("name_zh") or "").strip()

    candidates: dict[str, dict[str, Any]] = {
        source_id: {"urls": [], "commons_terms": [], "manual": MANUAL_LOCAL_MEALS.get(source_id)}
        for source_id in curated_ids
    }
    with pool_path.open(encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream):
            source_id = row["source_recipe_id"]
            if source_id not in candidates:
                continue
            try:
                urls = json.loads(row.get("image_urls") or "[]")
            except json.JSONDecodeError:
                urls = []
            candidates[source_id]["urls"] = [url for url in urls if isinstance(url, str) and url.startswith("https://")]
    restore_from_raw_zip(candidates)
    names_by_id = read_reviewed_names()
    for source_id, candidate in candidates.items():
        if candidate["urls"] or candidate["manual"]:
            continue
        terms: list[str] = list(MANUAL_COMMONS_QUERIES.get(source_id, ()))
        name_en = names_by_id.get(source_id)
        if name_en:
            terms.append(name_en)
        name_zh = curated_zh.get(source_id)
        if name_zh and name_zh != name_en:
            terms.append(name_zh)
        candidate["commons_terms"] = terms
    if len(candidates) != 295:
        raise ValueError(f"餐食审核 ID 数量异常，期望 295，实际 {len(candidates)}")
    return candidates


def restore_from_raw_zip(candidates: dict[str, dict[str, Any]]) -> None:
    """processed 池中被清空的图片 URL 从原始数据包 recipes.csv 的 Images 列恢复
    （如 MANUAL_MEAL_IDS 中的 96740/198328；数据包不存在时保持现状）。"""
    raw_zip = ROOT / "data" / "meal" / "raw" / "foodcom-recipes-and-reviews-v2.zip"
    if not raw_zip.is_file():
        return
    pending = {source_id: candidate for source_id, candidate in candidates.items() if not candidate["urls"]}
    if not pending:
        return
    restored = 0
    with zipfile.ZipFile(raw_zip) as archive:
        reader = csv.reader(io.TextIOWrapper(archive.open("recipes.csv"), encoding="utf-8"))
        header = next(reader)
        columns = {name: i for i, name in enumerate(header)}
        id_index = columns["RecipeId"]
        images_index = columns["Images"]
        for row in reader:
            source_id = row[id_index]
            if source_id not in pending:
                continue
            images = row[images_index].strip()
            urls: list[str] = []
            if images and images != "character(0)":
                if images.startswith("c("):
                    urls = re.findall(r'"([^"]+)"', images)
                else:
                    try:
                        parsed = json.loads(images)
                    except json.JSONDecodeError:
                        parsed = [images.strip('"')]
                    urls = parsed if isinstance(parsed, list) else [parsed]
            urls = [url for url in urls if url.startswith("https://")]
            if urls:
                pending[source_id]["urls"] = urls
                restored += 1
            del pending[source_id]
            if not pending:
                break
    if restored:
        print(f"原始数据包恢复餐食图片 URL：{restored} 条", flush=True)


def read_reviewed_names() -> dict[str, str]:
    """从审核 seed 的餐食行读取 source_id -> name_en（与 build_corpus_manifest.py 同源输入）。"""
    seed_path = ROOT / "src" / "main" / "resources" / "db" / "seed" / "reviewed_resources.sql"
    names: dict[str, str] = {}
    with seed_path.open(encoding="utf-8") as stream:
        for line in stream:
            stripped = line.strip()
            if not stripped.startswith("('PUBLIC'"):
                continue
            fields = next(csv.reader([stripped], quotechar="'", skipinitialspace=True))
            if len(fields) < 29:
                continue
            names[fields[28]] = fields[3]
    return names


def extension_from_content_type(content_type: str) -> str:
    normalized = content_type.lower().split(";", 1)[0].strip()
    return {
        "image/jpeg": ".jpg",
        "image/png": ".png",
        "image/gif": ".gif",
        "image/webp": ".webp",
    }.get(normalized, ".jpg")


def commons_search_images(term: str) -> list[tuple[str, str, str]]:
    """Wikimedia Commons 检索，返回 [(原图 URL, 800px 缩略图 URL, 文件标题)]；
    filetype:bitmap 过滤掉 PDF 等非位图，原图 URL 去除追踪参数。"""
    params = {
        "action": "query", "format": "json", "generator": "search",
        "gsrnamespace": "6", "gsrlimit": "10", "gsrsearch": term + " filetype:bitmap",
        "prop": "imageinfo", "iiprop": "url|mime", "iiurlwidth": "800",
    }
    request = urllib.request.Request(COMMONS_API + "?" + urllib.parse.urlencode(params),
                                     headers={"User-Agent": COMMONS_UA})
    last_error: Exception | None = None
    for attempt in range(1, 5):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                payload = json.load(response)
            break
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, OSError) as error:
            last_error = error
            if isinstance(error, urllib.error.HTTPError) and error.code == 429:
                time.sleep(3 * attempt)
            else:
                time.sleep(attempt)
    else:
        raise RuntimeError(f"Wikimedia 检索失败：{term}") from last_error
    pages = (payload.get("query") or {}).get("pages") or {}
    results: list[tuple[str, str, str]] = []
    for page in sorted(pages.values(), key=lambda item: item.get("index", 0)):
        info = (page.get("imageinfo") or [{}])[0]
        mime = info.get("mime") or ""
        if mime not in ("image/jpeg", "image/png", "image/webp"):
            continue
        url = (info.get("url") or "").split("?")[0]
        thumb = info.get("thumburl") or url
        if url:
            results.append((url, thumb.split("?")[0], str(page.get("title") or "")))
    return results


# Commons 文件标题词重叠打分用的噪声词（不含食材/菜式词，避免误杀真实匹配）
_COMMONS_NOISE = frozenset(
    "with and or over of the a an in on for from to at by low fat carb calorie "
    "healthy easy quick style adapted cooking light made making homemade home "
    "recipe recipes food dish dishes plate platter bowl restaurant vegan "
    "vegetarian non gluten free day week years old new photo picture image".split()
)


def dish_tokens(terms: list[str]) -> tuple[frozenset[str], str]:
    """菜名词元：英文词集合 + 中文名原文（用于与 Commons 文件标题做重叠打分）。"""
    words: set[str] = set()
    english = next((t for t in terms if re.search(r"[A-Za-z]", t)), "")
    zh = next((t for t in terms if re.search(r"[\u4e00-\u9fff]", t)), "")
    for word in re.findall(r"[A-Za-z]{3,}", english):
        lowered = word.lower()
        if lowered not in _COMMONS_NOISE:
            words.add(lowered)
    return frozenset(words), zh


def title_overlap_score(title: str, tokens: frozenset[str], zh: str) -> int:
    """文件标题与菜名的重叠词数；标题含中文名原文另 +1。"""
    words = {w.lower() for w in re.findall(r"[A-Za-z]{3,}", urllib.parse.unquote(title))
             if w.lower() not in _COMMONS_NOISE}
    score = len(words & tokens)
    if zh and zh in urllib.parse.unquote(title):
        score += 1
    return score


def pick_commons_image(terms: list[str], trusted_first: tuple[str, ...] = (),
                       result_index: int = 0) -> tuple[str, str, str, str] | None:
    """在所有变体检索结果中按 文件标题 ↔ 菜名 重叠打分选最优图；
    无任何标题重叠时返回 None（宁缺毋滥，避免把无关图片放进演示库）。
    trusted_first 为人工核准的检索词（MANUAL_COMMONS_QUERIES）：命中即取第
    result_index+1 个位图（默认第一个），不要求标题重叠——人工已担保该词的类型正确
    （如 "toothfish" 即智利海鲈），偏移用于兄弟菜同图去重。"""
    tokens, zh = dish_tokens(terms)
    for term in trusted_first:
        try:
            results = commons_search_images(term)
        except RuntimeError:
            time.sleep(1.0)
            continue
        time.sleep(0.4)
        if results:
            url, thumb, title = results[min(result_index, len(results) - 1)]
            return url, thumb, title, term
    best: tuple[int, str, str, str, str] | None = None  # (score, term, url, thumb, title)
    for term in commons_query_variants(terms):
        if term in trusted_first:
            continue
        try:
            results = commons_search_images(term)
        except RuntimeError:
            time.sleep(1.0)
            continue
        time.sleep(0.4)
        for url, thumb, title in results:
            score = title_overlap_score(title, tokens, zh)
            if score <= 0:
                continue
            if best is None or score > best[0]:
                best = (score, term, url, thumb, title)
    if best is None:
        return None
    _, term, url, thumb, title = best
    return url, thumb, title, term


def read_manifest_sources() -> dict[str, str]:
    """现有 MEDIA_MANIFEST 中已记录的餐食来源 URL（幂等重跑时保持逐条可追溯）。"""
    manifest_path = ROOT / "frontend" / "assets" / "media" / "MEDIA_MANIFEST.json"
    if not manifest_path.is_file():
        return {}
    try:
        payload = json.loads(manifest_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}
    return {
        str(item.get("source_id")): str(item.get("source_url"))
        for item in (payload.get("meal_source") or {}).get("items") or []
        if item.get("source_url")
    }


def commons_query_variants(terms: list[str]) -> list[str]:
    """按优先级生成 Wikimedia 检索词：全名 → 逐级去掉开头修饰词（保留菜式主体）
    → 去掉末尾词 → 中文名。例："Ginger Lemon Poached Halibut" → "Poached Halibut"、
    "Cajun Red Snapper" → "Red Snapper"、"Yogurt Strawberry Blast" → "Yogurt Strawberry"。"""
    variants: list[str] = []
    for term in terms:
        if not term:
            continue
        if term not in variants:
            variants.append(term)
        words = term.split()
        if len(words) > 3:
            for drop in (1, 2):
                tail = " ".join(words[drop:])
                if tail and tail not in variants:
                    variants.append(tail)
            head = " ".join(words[:-1])
            if head and head not in variants:
                variants.append(head)
        elif len(words) == 3:
            tail = " ".join(words[1:])
            if tail not in variants:
                variants.append(tail)
            head = " ".join(words[:-1])
            if head not in variants:
                variants.append(head)
        elif len(words) == 2:
            tail = words[-1]
            if tail not in variants:
                variants.append(tail)
    return variants


def download_meal(source_id: str, source: dict[str, Any], target: Path,
                  existing_sources: dict[str, str]) -> MealDownload:
    """下载单条餐食图片：本地样本映射 > 已有文件（幂等复用） > Food.com URL > Wikimedia 检索。"""
    manual = source.get("manual")
    if manual:
        local_url, source_url = manual
        path = ROOT / "frontend" / local_url.lstrip("/")
        if not path.is_file():
            raise FileNotFoundError(f"本地样本缺失：{path}")
        require_image(path)
        return MealDownload(source_id, source_url, local_url, sha256(path), "LICENSED",
                            "本地既有样本（frontend/assets/media/reviewed/README.md 记录来源）", MANUAL_MEAL_CREDIT)

    urls = source["urls"]
    credit = FOOD_MEAL_CREDIT if urls else WIKIMEDIA_MEAL_CREDIT
    existing = next(target.glob(f"{source_id}.*"), None)
    if existing and existing.is_file():
        require_image(existing)
        return MealDownload(source_id, existing_sources.get(source_id), media_url(existing),
                            sha256(existing), "LICENSED", None, credit)
    last_error: Exception | None = None
    for source_url in urls:
        request = urllib.request.Request(source_url, headers={"User-Agent": "health-agent-local-ui-media/1.0"})
        try:
            with urllib.request.urlopen(request, timeout=25) as response:
                content_type = response.headers.get_content_type()
                payload = response.read()
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, OSError) as error:
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
        return MealDownload(source_id, source_url, media_url(path), sha256(path), "LICENSED", None, credit)
    picked = pick_commons_image(source["commons_terms"], tuple(MANUAL_COMMONS_QUERIES.get(source_id, ())),
                                MANUAL_COMMONS_RESULT_INDEX.get(source_id, 0))
    if picked is not None:
        source_url, thumb_url, title, term = picked
        path: Path | None = None
        try:
            request = urllib.request.Request(thumb_url, headers={"User-Agent": COMMONS_UA})
            with urllib.request.urlopen(request, timeout=40) as response:
                content_type = response.headers.get_content_type()
                payload = response.read()
            extension = extension_from_content_type(content_type)
            path = target / f"{source_id}{extension}"
            path.write_bytes(payload)
            require_image(path)
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, OSError, ValueError) as error:
            last_error = error
            if path is not None:
                path.unlink(missing_ok=True)
        else:
            return MealDownload(source_id, source_url, media_url(path), sha256(path), "LICENSED",
                                f"Wikimedia Commons 检索词: {term}（{title}）", credit)
    if urls:
        return MealDownload(source_id, urls[0], None, None, "DOWNLOAD_FAILED", str(last_error))
    return MealDownload(source_id, None, None, None, "MISSING_SOURCE",
                        "原始数据无图片 URL 且 Wikimedia Commons 无标题匹配的位图")


def sync_meal_media(workers: int, offset: int = 0, limit: int = 0) -> list[MealDownload] | None:
    target = ROOT / "frontend" / "assets" / "media" / "meals"
    target.mkdir(parents=True, exist_ok=True)
    candidates = meal_candidates()
    tasks = sorted(candidates.items(), key=lambda item: int(item[0]))
    existing_sources = read_manifest_sources()

    def safe_download(source_id: str, source: dict[str, Any]) -> MealDownload:
        try:
            return download_meal(source_id, source, target, existing_sources)
        except Exception as error:
            return MealDownload(source_id, None, None, None, "DOWNLOAD_FAILED", f"未预期异常：{error!r}")

    if limit > 0:
        selected = tasks[offset:offset + limit]
        with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, workers)) as executor:
            for completed, _ in enumerate(executor.map(
                    lambda item: safe_download(item[0], item[1]), selected), start=1):
                if completed % 50 == 0 or completed == len(selected):
                    print(f"餐食媒体分批下载进度：{offset + completed} / {len(tasks)}", flush=True)
        return None
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, workers)) as executor:
        futures = [executor.submit(safe_download, source_id, source) for source_id, source in tasks]
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
            f"    media_credit = {sql_literal(meal.credit or FOOD_MEAL_CREDIT)}, updated_at = NOW()",
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
    if args.skip_meals:
        meal_summary = "未同步"
    else:
        from collections import Counter
        state_summary = Counter(item.state for item in meals)
        meal_summary = " / ".join(f"{state} {state_summary[state]}" for state in sorted(state_summary))
    print(f"动作媒体：{len(media)} 张 JPG + {len(media)} 个 GIF；餐食媒体：{meal_summary}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"同步失败：{error}", file=sys.stderr)
        raise SystemExit(1)
