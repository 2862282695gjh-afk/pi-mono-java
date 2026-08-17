# Device fixtures (canonical)

Each file is named **`{deviceId}.json`** (e.g. `EF_001.json`, `VAV_001.json`).

| File | Content |
|------|---------|
| `registry.json` | Device metadata + `legacyIds` (not time series) |
| `<deviceId>.json` | All BMS point time series for that device |

## ID format

Canonical **`deviceId`** = ASCII `{typeCode}_{seq:03d}`，例如 `VAV_001`、`EF_001`、`SF_001`、`PAHU_001`、`AHU_001`。  
中文伪 ID（`送排风_排风机` 等）与旧 hyphen ID 保留在 `legacyIds`，mock 仍可解析。

## `<deviceId>.json` schema (v2)

```json
{
  "version": 2,
  "deviceId": "VAV_003",
  "deviceType": "VAV",
  "component": "CO2传感器",
  "scenario": "fault",
  "data": [
    { "ts": 1779958409.1, "points": { "zoneCO2": 2100.0 } }
  ],
  "ruleSeries": {
    "vav_co2_sensor_co2_8f11a2d8": [
      { "ts": 1779958409.1, "points": { "zoneCO2": 22.0 } }
    ]
  }
}
```

- **`data`** — merged superset of all points on the device (inventory / default series).
- **`ruleSeries`** — optional per-rule time series when the same point needs different values for different rules (mock API prefers `ruleSeries[requestId]`).

Mock API loads `devices/<deviceId>.json`, selects series by rule `requestId`, then **projects** only the `points[]` the rule requested.

## Demo fleet (12 devices)

| deviceId | deviceType | component | building |
|----------|------------|-----------|----------|
| VAV_001 | VAV | 风阀 | A栋 |
| VAV_002 | VAV | 温度传感器 | A栋 |
| VAV_003 | VAV | CO2传感器 | B栋 |
| PAHU_001 | 新风机 | 送风机变频器 | 能源中心 |
| AHU_001 | AHU | 送风压力传感器 | 能源中心 |
| EF_001 | 送排风 | 排风机 | 能源中心 |
| SF_001 | 送排风 | 送风机 | 能源中心 |
| FCU_001 | FCU | 风机盘管 | C栋 |
| CHW_001 | CHILLER | 冷水机组 | 能源中心 |
| CT_001 | CT | 冷却塔 | 能源中心 |
| BOILER_001 | BOILER | 锅炉 | 能源中心 |
| CHWP_001 | CHWP | 冷冻水泵 | 能源中心 |

## Migrate legacy rule-keyed fixtures

```bash
python scripts/consolidate_device_fixtures.py --source-dir path/to/legacy/rule/fixtures
python scripts/consolidate_device_fixtures.py --clean   # remove rule files under fixtures root
```

Demo healthy devices: edit `scenario_overrides.json` (`healthyRuleIds`) and re-run consolidate.

Legacy `<rule.id>.json` at `mock_fixtures/` root is deprecated.
