"""
Inspection device scope counts — synced with device-inspection-re/scripts/inspection_scope.py.
"""
from __future__ import annotations

from typing import Any, Dict, List, Optional


def registry_devices_in_scope(
    registry_doc: Dict[str, Any],
    device_type_filter: Optional[List[str]] = None,
) -> List[Dict[str, Any]]:
    devices = [
        row
        for row in (registry_doc.get("devices") or [])
        if isinstance(row, dict) and str(row.get("deviceId", "")).strip()
    ]
    if not device_type_filter:
        return devices
    allowed = set(device_type_filter)
    return [row for row in devices if str(row.get("deviceType", "")).strip() in allowed]


def build_inspection_scope_summary(
    *,
    registry_doc: Dict[str, Any],
    fault_device_ids: List[str],
    device_type_filter: Optional[List[str]] = None,
    total_alert_count: int = 0,
) -> Dict[str, Any]:
    scope_devices = registry_devices_in_scope(registry_doc, device_type_filter)
    scope_ids = sorted(str(row.get("deviceId", "")).strip() for row in scope_devices)
    fault_set = {str(item).strip() for item in fault_device_ids if str(item).strip()}
    fault_in_scope = sorted(device_id for device_id in scope_ids if device_id in fault_set)
    healthy_in_scope = sorted(device_id for device_id in scope_ids if device_id not in fault_set)
    inspected_count = len(scope_ids)
    fault_count = len(fault_in_scope)
    healthy_count = len(healthy_in_scope)
    if device_type_filter:
        scope_label = "、".join(device_type_filter)
    else:
        scope_label = "全部设备"
    return {
        "scopeLabel": scope_label,
        "deviceTypeFilter": device_type_filter,
        "inspectedDeviceCount": inspected_count,
        "faultDeviceCount": fault_count,
        "healthyDeviceCount": healthy_count,
        "totalAlertCount": total_alert_count,
        "healthScore": f"{healthy_count}/{inspected_count}" if inspected_count > 0 else "N/A",
        "healthPercentage": round(healthy_count / inspected_count * 100, 1) if inspected_count > 0 else 0.0,
        "scopeDeviceIds": scope_ids,
        "faultDevices": fault_in_scope,
        "healthyDevices": healthy_in_scope,
    }
