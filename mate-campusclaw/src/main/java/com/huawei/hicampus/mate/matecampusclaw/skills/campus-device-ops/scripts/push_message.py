from __future__ import annotations

from collections import defaultdict
from typing import Any, Dict, List


def _first_line(text: str, *, limit: int = 80) -> str:
    line = str(text or "").replace("\\n", "\n").split("\n")[0].strip()
    if len(line) > limit:
        return line[: limit - 1] + "…"
    return line or "—"


def _group_items_by_device(items: List[Dict[str, Any]]) -> Dict[str, List[Dict[str, Any]]]:
    grouped: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for item in items or []:
        if isinstance(item, dict):
            grouped[str(item.get("deviceId", ""))].append(item)
    return grouped


def render_ai_message(digest: Dict[str, Any], *, run_id: str = "") -> str:
    """Build assignee push text from digest items only (templates/push-digest.template.md)."""
    assignee_name = str(digest.get("assigneeName", "")).strip() or str(digest.get("assigneeId", ""))
    items = list(digest.get("items") or [])
    alarm_count = int(digest.get("alarmCount", len(items)) or len(items))

    buildings = sorted({str(i.get("building", "")).strip() for i in items if i.get("building")})
    building_label = buildings[0] if len(buildings) == 1 else "、".join(buildings) if buildings else "园区"

    role = str(digest.get("assigneeRole", "")).strip()
    header = f"【{building_label}-{role or assignee_name}】{alarm_count}条设施告警需处理"
    if run_id:
        header += f"（runId={run_id}）"

    lines: List[str] = [header, ""]
    grouped = _group_items_by_device(items)
    for device_id in sorted(grouped.keys()):
        device_items = grouped[device_id]
        sample = device_items[0]
        device_name = str(sample.get("deviceName", device_id)).strip()
        rule_names = [str(x.get("ruleName", "")).strip() for x in device_items if x.get("ruleName")]
        priorities = {str(x.get("priority", "low")) for x in device_items}
        pri = "high" if "high" in priorities else ("medium" if "medium" in priorities else "low")
        if len(device_items) == 1:
            lines.append(
                f"- {device_name}（{device_id}）：{rule_names[0]}，优先级{pri}"
            )
        else:
            preview = "、".join(rule_names[:3])
            if len(rule_names) > 3:
                preview += f"等{len(rule_names)}项"
            lines.append(
                f"- {device_name}（{device_id}）：{len(device_items)}条告警（{preview}），含优先级{pri}"
            )

    advice_lines: List[str] = []
    for item in items[:3]:
        tip = _first_line(str(item.get("expertAdvice", "")))
        if tip != "—" and tip not in advice_lines:
            advice_lines.append(tip)
    lines.append("")
    lines.append(f"建议：{'；'.join(advice_lines) if advice_lines else '请按规程现场核查并记录。'}")
    lines.append("")
    lines.append("请于30分钟内现场确认或创建工单跟进。")
    return "\n".join(lines)
