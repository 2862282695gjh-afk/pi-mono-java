"""
rules_re.json path resolution — synced with campus-device-ops/scripts/rules_re_paths.py.
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional


class RulesNotFoundError(FileNotFoundError):
    def __init__(
        self,
        message: str,
        *,
        searched_paths: List[str],
        explicit_path: Optional[str] = None,
    ) -> None:
        super().__init__(message)
        self.searched_paths = searched_paths
        self.explicit_path = explicit_path


RULES_NOT_FOUND_USER_HINT = (
    "未找到规则文件 rules_re.json，无法执行设备巡检。\n"
    "请先使用 excel-antlr-to-rules-json 技能从「故障规则.xlsx」编译生成 rules_re.json，"
    "或将其放到 device-inspection-re/rules/rules_re.json。\n"
    "也可设置环境变量 DEVICE_INSPECTION_RE_RULES_PATH 或 CAMPUS_OPS_RULES_PATH 指向该文件。\n"
    "请将以上说明转达用户，并立即结束本次设备巡检 skill 调用（勿继续执行统计、报告、工单等后续步骤）。"
)

_RULES_RE_REL = (
    "skills/device-inspection-re/rules/rules_re.json",
    "rule-engines-pack/02-rule-engine-pypi/rules/rules_re.json",
)


def skill_root() -> Path:
    return Path(__file__).resolve().parents[1]


def workspace_root() -> Path:
    # .../skills/<skill>/scripts/this.py -> parent of skills/
    return Path(__file__).resolve().parents[2].parent


def campusclaw_root() -> Path:
    return workspace_root()


def device_inspection_re_skill_root() -> Path:
    env = os.environ.get("DEVICE_INSPECTION_RE_SKILL_ROOT", "").strip()
    if env:
        return Path(env).expanduser().resolve()
    root = skill_root()
    if root.name == "device-inspection-re":
        return root
    sibling = root.parent / "device-inspection-re"
    if sibling.is_dir():
        return sibling
    openclaw = Path.home() / ".openclaw" / "workspace" / "skills" / "device-inspection-re"
    if openclaw.is_dir():
        return openclaw
    return sibling


def bundled_rules_re_path() -> Path:
    return device_inspection_re_skill_root() / "rules" / "rules_re.json"


def rules_re_candidate_paths() -> List[Path]:
    paths: List[Path] = []
    seen: set[str] = set()

    def add(path: Path) -> None:
        key = str(path)
        if key not in seen:
            seen.add(key)
            paths.append(path)

    for env_key in ("DEVICE_INSPECTION_RE_RULES_PATH", "CAMPUS_OPS_RULES_PATH"):
        raw = os.environ.get(env_key, "").strip()
        if raw:
            add(Path(raw).expanduser().resolve())

    add(bundled_rules_re_path())

    root = workspace_root()
    for base in (root, *root.parents):
        for rel in _RULES_RE_REL:
            add(base / rel)

    add(Path.home() / ".openclaw" / "workspace" / "skills" / "device-inspection-re" / "rules" / "rules_re.json")
    return paths


def resolve_rules_re_path(explicit: Optional[Path] = None) -> Path:
    searched = [str(path) for path in rules_re_candidate_paths()]
    if explicit is not None:
        resolved = explicit.expanduser().resolve()
        if resolved.is_file():
            return resolved
        message = (
            f"{RULES_NOT_FOUND_USER_HINT}\n\n"
            f"指定路径不存在：{resolved}\n"
            f"已尝试的默认位置：\n" + "\n".join(f"  - {item}" for item in searched)
        )
        raise RulesNotFoundError(
            message,
            searched_paths=searched,
            explicit_path=str(resolved),
        )

    for candidate in rules_re_candidate_paths():
        if candidate.is_file():
            return candidate
    message = (
        f"{RULES_NOT_FOUND_USER_HINT}\n\n"
        f"已搜索以下位置，均未找到 rules_re.json：\n" + "\n".join(f"  - {item}" for item in searched)
    )
    raise RulesNotFoundError(message, searched_paths=searched)


def emit_rules_not_found(*, err: RulesNotFoundError, json_mode: bool) -> None:
    payload: Dict[str, Any] = {
        "error": "rules_not_found",
        "message": str(err),
        "searchedPaths": err.searched_paths,
        "action": "stop_skill",
        "userHint": RULES_NOT_FOUND_USER_HINT,
    }
    if err.explicit_path:
        payload["explicitPath"] = err.explicit_path
    if json_mode:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        print(str(err), file=sys.stderr)
