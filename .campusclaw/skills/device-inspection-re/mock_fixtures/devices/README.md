# Device fixtures (canonical)

Each file is named **`{deviceId}.json`** (e.g. `EF-001.json`, `VAV-DMP-101.json`).

| File | Content |
|------|---------|
| `registry.json` | Device metadata + `legacyIds` (not time series) |
| `<deviceId>.json` | All BMS point time series for that device |

## `<deviceId>.json` schema (v2)

```json
{
  "version": 2,
  "deviceId": "VAV-CO2-201",
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

## Migrate legacy rule-keyed fixtures

```bash
python scripts/consolidate_device_fixtures.py --source-dir path/to/legacy/rule/fixtures
python scripts/consolidate_device_fixtures.py --clean   # remove rule files under fixtures root
```

Demo healthy devices: edit `scenario_overrides.json` (`healthyRuleIds`) and re-run consolidate.

Legacy `<rule.id>.json` at `mock_fixtures/` root is deprecated.
