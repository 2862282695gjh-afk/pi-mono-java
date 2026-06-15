"""
Delete excel-antlr-to-rules-json intermediate files under OpenClaw/CampusClaw workspace.

Cleans:
  - rules/: _candidate_rules_re.json, compile_report*.json, patches.json, ...
  - skills/: *_patched*.xlsx, *_编译用.xlsx (apply_excel_patch / 改表另存)

Run after rules_re.json is written and verified. Keeps original 故障规则.xlsx.
"""
from __future__ import annotations

import sys
from pathlib import Path

_RULES_INTERMEDIATE_FILES = (
    "_candidate_rules_re.json",
    "compile_report.json",
    "_test_rules_re.json",
    "_test_compile_report.json",
    "patches.json",
)

_RULES_INTERMEDIATE_GLOB = "compile_report_*.json"

# apply_excel_patch.py default: <stem>_patched.xlsx; repeat: <stem>_patched_1.xlsx
_SKILLS_PATCHED_GLOBS = (
    "*_patched.xlsx",
    "*_patched_*.xlsx",
    "*_编译用.xlsx",
)

# Never delete files inside the packaged skill tree (templates/examples only)
_SKILL_PACKAGE_MARKERS = (
    "excel-antlr-to-rules-json/templates",
    "excel-antlr-to-rules-json/examples",
    "excel-antlr-to-rules-json/grammar",
    "excel-antlr-to-rules-json/generated",
)


def _under_skill_package(path: Path, workspace: Path) -> bool:
    try:
        rel = path.resolve().relative_to(workspace.resolve()).as_posix()
    except ValueError:
        return False
    return any(marker in rel for marker in _SKILL_PACKAGE_MARKERS)


def cleanup_rules_dir(rules_dir: Path, *, dry_run: bool = False) -> list[Path]:
    removed: list[Path] = []
    if not rules_dir.is_dir():
        raise FileNotFoundError(f"rules directory not found: {rules_dir}")

    candidates: list[Path] = [rules_dir / name for name in _RULES_INTERMEDIATE_FILES]
    for path in sorted(rules_dir.glob(_RULES_INTERMEDIATE_GLOB)):
        if path.name != "compile_report.json":
            candidates.append(path)

    return _unlink_candidates(candidates, dry_run=dry_run, removed=removed)


def cleanup_excel_patches(skills_dir: Path, workspace: Path, *, dry_run: bool = False) -> list[Path]:
    removed: list[Path] = []
    if not skills_dir.is_dir():
        return removed

    candidates: list[Path] = []
    for pattern in _SKILLS_PATCHED_GLOBS:
        for path in skills_dir.rglob(pattern):
            if not path.is_file():
                continue
            if _under_skill_package(path, workspace):
                continue
            candidates.append(path)

    return _unlink_candidates(candidates, dry_run=dry_run, removed=removed)


def _unlink_candidates(candidates: list[Path], *, dry_run: bool, removed: list[Path]) -> list[Path]:
    seen: set[Path] = set()
    for path in candidates:
        resolved = path.resolve()
        if resolved in seen or not path.is_file():
            continue
        seen.add(resolved)
        if dry_run:
            print(f"would remove: {path}")
        else:
            path.unlink()
            print(f"removed: {path}")
        removed.append(path)
    return removed


def cleanup_workspace(workspace: Path, *, dry_run: bool = False) -> list[Path]:
    rules_dir = workspace / "rules"
    skills_dir = workspace / "skills"
    removed: list[Path] = []
    if rules_dir.is_dir():
        removed.extend(cleanup_rules_dir(rules_dir, dry_run=dry_run))
    removed.extend(cleanup_excel_patches(skills_dir, workspace, dry_run=dry_run))
    return removed


def _resolve_workspace(args: list[str]) -> Path:
    if args:
        return Path(args[0]).expanduser().resolve()
    cwd = Path.cwd()
    if (cwd / "rules").is_dir() or (cwd / "skills").is_dir():
        return cwd
    return Path(__file__).resolve().parents[4]


def main(argv: list[str]) -> int:
    import argparse

    p = argparse.ArgumentParser(
        description="Delete excel-antlr-to-rules-json intermediate files (rules/ + skills/)",
        epilog="Keeps rules_re.json and the original 故障规则.xlsx. Run after verifying rules_re.json.",
    )
    p.add_argument("workspace", nargs="?", default=None, help="Workspace dir (default: cwd or OPENCLAW workspace)")
    p.add_argument("--dry-run", action="store_true", help="Show what would be deleted without deleting")
    args = p.parse_args(argv[1:])

    workspace = Path(args.workspace).expanduser().resolve() if args.workspace else _resolve_workspace([])
    try:
        removed = cleanup_workspace(workspace, dry_run=args.dry_run)
    except FileNotFoundError as e:
        print(str(e))
        return 2

    if not removed:
        print(f"no skill intermediates under {workspace}")
    else:
        print(f"done: {len(removed)} file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
