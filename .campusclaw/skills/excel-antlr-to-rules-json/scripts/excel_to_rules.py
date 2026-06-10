from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any, Dict, List

from compile_trigger import compile_trigger_formula
from emit_rule_engine import merge_points_with_expression
from excel_io import (
    collect_point_keys,
    normalize_header_map,
    parse_duration_seconds,
    parse_effective_data,
    parse_ratio,
    read_all_xlsx_sheets,
    row_with_standard_keys,
    stable_rule_id,
)


def _configure_stdio_utf8() -> None:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except Exception:
            pass


_HINT_PATTERNS: list[tuple[str, str]] = [
    (r"Ni-Ni-1", "恒定判断应写为 [点位名] == prev([点位名])，参见 agent-guide §2.1"),
    (r"\|.*\|", "绝对值符号 |...| 应改为 abs(...)，参见 agent-guide §2.2"),
    (r"[℃uSkPa]", "公式中不应包含单位符号（℃/uS/kPa 等），去掉即可"),
    (r"或|并且", "中文逻辑运算符应改为 ||（或）或 &&（并且）"),
    (r"\bAND\b|\bOR\b", "AND/OR 应改为 &&/||（小写也不行，必须是符号）"),
    (r"\band\b|\bor\b", "and/or 应改为 &&/||"),
    (r"PPM|ppm", "公式中不应包含单位 PPM，去掉即可"),
    (r"_last", "不要用 _last 后缀，应使用 prev() 函数"),
]


def _generate_hint(formula: str, errors: list[dict]) -> str:
    """Generate a human-readable hint based on common error patterns."""
    import re as _re
    hints: list[str] = []
    for pattern, hint in _HINT_PATTERNS:
        if _re.search(pattern, formula):
            hints.append(hint)
    for err in errors:
        msg = str(err.get("message", ""))
        if "token recognition error" in msg and "' '" in msg:
            hints.append("公式中有多余空格或不可见字符，检查单元格是否有换行或 NBSP")
    return "; ".join(dict.fromkeys(hints))  # dedupe preserving order


def compile_excel_to_rules(xlsx_path: Path) -> tuple[Dict[str, Any], Dict[str, Any]]:
    """
    Read Excel, ANTLR-parse each trigger_formula, emit rules_re.json (trigger.rule_engine).
    """
    sheets_data = read_all_xlsx_sheets(xlsx_path)
    rows: List[Dict[str, Any]] = []
    sheet_summaries: List[Dict[str, Any]] = []
    for sheet_name, headers, raw_rows in sheets_data:
        header_map = normalize_header_map(headers)
        for raw in raw_rows:
            std = row_with_standard_keys(raw, header_map)
            std["__excel_sheet__"] = sheet_name
            rows.append(std)
        sheet_summaries.append({"name": sheet_name, "data_rows": len(raw_rows), "ok": 0, "failed": 0})

    report: Dict[str, Any] = {
        "excel": str(xlsx_path),
        "sheets": sheet_summaries,
        "summary": {"total": len(rows), "ok": 0, "failed": 0},
        "rows": [],
    }
    rules: List[Dict[str, Any]] = []

    for row in rows:
        excel_row = int(row.get("__excel_row__", 0))
        excel_sheet = str(row.get("__excel_sheet__", "")).strip()
        entry: Dict[str, Any] = {
            "sheet": excel_sheet,
            "row": excel_row,
            "status": "ok",
            "errors": [],
        }
        try:
            device_type = str(row.get("device_type", "")).strip()
            component = str(row.get("component", "")).strip()
            name = str(row.get("fault_name", "")).strip()
            if not (device_type and component and name):
                raise ValueError("missing device_type, component, or fault_name")

            point_keys = collect_point_keys(row)
            if len(point_keys) < 1:
                raise ValueError("need at least point_1 or 点位 column")

            rule_id = str(row.get("rule_id", "")).strip() or stable_rule_id(device_type, component, name)
            entry["rule_id"] = rule_id

            effective_raw = row.get("effective_data")
            if effective_raw is not None and str(effective_raw).strip():
                window_seconds, threshold = parse_effective_data(effective_raw)
            else:
                window_raw = str(row.get("window", "")).strip()
                if not window_raw:
                    raise ValueError("missing window or effective_data")
                window_seconds = parse_duration_seconds(window_raw)
                threshold = parse_ratio(row.get("effective_ratio"))

            trigger_formula = str(row.get("trigger_formula", "")).strip()
            _ast, rule_engine_text, antlr_errors = compile_trigger_formula(trigger_formula, point_keys)
            if antlr_errors or not rule_engine_text:
                for msg in antlr_errors:
                    entry["errors"].append(
                        {
                            "field": "trigger_formula",
                            "message": msg,
                            "value": trigger_formula,
                        }
                    )
                raise ValueError("ANTLR compile failed")

            points_merged = merge_points_with_expression(point_keys, rule_engine_text)

            effective: Dict[str, Any] = {
                "metric": "ratio_true",
                "threshold": float(threshold),
            }
            min_samples_raw = row.get("min_samples", None)
            if min_samples_raw is not None and str(min_samples_raw).strip() != "":
                effective["minSamples"] = int(min_samples_raw)
            else:
                effective["minSamples"] = 1

            reason_analysis = str(row.get("reason_analysis", "") or "").strip()
            expert_advice = str(row.get("expert_advice", "") or "").strip()

            rule = {
                "id": rule_id,
                "name": name,
                "原因分析": reason_analysis,
                "专家处理建议": expert_advice,
                "meta": {
                    "deviceType": device_type,
                    "component": component,
                    "points": points_merged,
                },
                "window": {"type": "rolling", "durationSeconds": int(window_seconds)},
                "trigger": {"rule_engine": rule_engine_text},
                "effective": effective,
            }
            entry["rule_engine"] = rule_engine_text
            rules.append(rule)
            report["summary"]["ok"] += 1
            for sh in sheet_summaries:
                if sh["name"] == excel_sheet:
                    sh["ok"] += 1
                    break
        except Exception as e:  # noqa: BLE001
            entry["status"] = "failed"
            if not entry["errors"]:
                entry["errors"].append({"field": "*", "message": str(e)})
            # Generate hint for common error patterns
            formula = str(row.get("trigger_formula", "")).strip()
            hint = _generate_hint(formula, entry["errors"])
            if hint:
                entry["hint"] = hint
            report["summary"]["failed"] += 1
            for sh in sheet_summaries:
                if sh["name"] == excel_sheet:
                    sh["failed"] += 1
                    break

        report["rows"].append(entry)

    doc = {"version": 1, "rules": rules}
    return doc, report


def main(argv: List[str]) -> int:
    import argparse

    _configure_stdio_utf8()
    p = argparse.ArgumentParser(
        description="Compile Excel fault rules table to rules_re.json (ANTLR + rule-engine)",
        epilog="Exit codes: 0 = success, 2 = compile errors (see compile_report.json)",
    )
    p.add_argument("input", help="Path to Excel file (故障规则.xlsx or patched variant)")
    p.add_argument("output", help="Output path for rules_re.json (e.g. rules/_candidate_rules_re.json)")
    p.add_argument("--report", default=None, help="Compile report output path (default: <output>_compile_report.json)")
    args = p.parse_args(argv[1:])

    xlsx_path = Path(args.input).expanduser().resolve()
    out_rules = Path(args.output).expanduser().resolve()
    report_path = Path(args.report).expanduser().resolve() if args.report else None

    doc, report = compile_excel_to_rules(xlsx_path)
    if report_path is None:
        report_path = out_rules.with_name(out_rules.stem + "_compile_report.json")

    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    sheet_summaries = report.get("sheets") or []
    sheet_line = ", ".join(
        f"{s.get('name', '?')}({s.get('data_rows', 0)} rows, ok={s.get('ok', 0)}, failed={s.get('failed', 0)})"
        for s in sheet_summaries
        if isinstance(s, dict)
    )
    print(f"excel sheets merged: {sheet_line or '(none)'}")

    failed = int(report["summary"]["failed"])
    if failed > 0:
        print(f"compile failed: {failed} row(s); see {report_path}")
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 2

    out_rules.parent.mkdir(parents=True, exist_ok=True)
    out_rules.write_text(json.dumps(doc, ensure_ascii=False, indent=2), encoding="utf-8")
    print(
        f"wrote rules_re: {out_rules} ({len(doc.get('rules', []))} rules from "
        f"{len(sheet_summaries)} worksheet(s))"
    )
    print(f"report: {report_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
