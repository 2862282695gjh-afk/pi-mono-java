---
name: excel-antlr-to-rules-json
description: >-
  将 Excel 故障规则表编译为 rules_re.json（ANTLR + rule-engine）。
  处理触发条件中的语法错误（中文单位、绝对值符号、恒定判断等），
  失败时自动读 agent-guide 改表并重跑。
  当用户提到「转化规则」「Excel 转规则」「编译规则表」「规则报错」「触发条件」「rules_re」时触发。
---

# Excel → ANTLR → `rules_re.json`

## 目录结构

```text
excel-antlr-to-rules-json/
├── SKILL.md                 ← 本文件（流程与命令）
├── reference.md             ← 仅维护者（改 .g4、重建 ANTLR）
├── requirements.txt
├── grammar/Trigger.g4
├── generated/               ← Trigger*.py（运行时依赖）
├── scripts/                 ← excel_to_rules.py 等
├── templates/
│   ├── agent-guide.md       ← 失败改表：LLM 只读这一篇
│   ├── rules_re-output-example.json
│   └── patches.example.json
└── examples/                ← 静态样例（无业务 xlsx）
```

**样例与业务表分离**：`templates/`、`examples/` 内设备/点位均为**虚构冷冻站**教学数据，与真实 `故障规则.xlsx` 无关；改表以 `compile_report.json` 为准，勿照抄样例点位名。

重构前完整备份：勿放在 `skills/` 下（会被 Agent 误读）；OpenClaw 示例路径  
`~/.openclaw/backups/excel-antlr-to-rules-json.backup-20260519/`

## 目标

`故障规则.xlsx` → **`rules_re.json`**（`trigger.rule_engine` 仅由脚本生成）。

## 硬规则

1. 禁止手写 `trigger.rule_engine`  
2. 失败 → 读 **`templates/agent-guide.md`** + `compile_report.json` → 改表 → **另存新 xlsx** → 重跑  
3. **禁止覆盖** `故障规则.xlsx`（用 `故障规则_patched.xlsx` 等）  
4. 不确定时每轮最多 1 个问题  
5. 全部成功 → `verify_rules_re.py` → 用户确认后写 `rules/rules_re.json` → **`cleanup_rules_intermediates.py` 删除中间产物**  
6. 读取 xlsx **所有含数据的工作表**（非仅激活页）；`compile_report.json` 含 `sheets[]`；成功时脚本打印 `excel sheets merged: ...`

## 流程

```text
故障规则.xlsx（只读）
    → excel_to_rules.py（可用 故障规则_patched.xlsx）
    → _candidate_rules_re.json + compile_report.json
    → verify_rules_re.py
    → 用户确认 → rules/rules_re.json
    → cleanup_rules_intermediates.py（删 rules 中间产物 + skills 下 patch xlsx）

可选分支：
rules_re.json → generate_mock_fixtures.py → mock_fixtures/（供 device-inspection-re 测试巡检）
```

## 中间产物（用后删除）

### `rules/`

| 文件 | 何时删 |
|------|--------|
| `rules_re.json` | **保留**（正式产出） |
| `_candidate_rules_re.json` | 已写入 `rules_re.json` 且验证通过后 |
| `compile_report.json` / `compile_report_*.json` | 全表编译成功且已确认后；失败时**保留**改表 |
| `_test_rules_re.json` / `_test_compile_report.json` | 试跑结束即删 |
| `patches.json` | `apply_excel_patch` 用完后 |

### Excel patch（`skills/` 等，**勿删原表**）

| 文件 | 来源 | 何时删 |
|------|------|--------|
| `故障规则_patched.xlsx` 等 `*_patched*.xlsx` | `apply_excel_patch.py` | 已用该文件编译成功并落盘 `rules_re.json` 后 |
| `*_编译用.xlsx` | 改表另存 | 同上 |
| **`故障规则.xlsx`** | 用户原表 | **永不删除** |

`rules/` 里 `_judge_result.json`、`_candidate_rules.json` 等**不是**本 skill 产出，本脚本不删。

## 命令

**路径说明**：`...` 代表 `${OPENCLAW_WORKSPACE}/skills/excel-antlr-to-rules-json`（Unix）或 `%OPENCLAW_WORKSPACE%\skills\excel-antlr-to-rules-json`（Windows）。脚本参数接受 `/` 或 `\`。

### Unix / macOS

```bash
pip install -r ${OPENCLAW_WORKSPACE}/skills/excel-antlr-to-rules-json/requirements.txt

python .../scripts/excel_to_rules.py \
  path/to/故障规则_patched.xlsx \
  ${OPENCLAW_WORKSPACE}/rules/_candidate_rules_re.json \
  --report ${OPENCLAW_WORKSPACE}/rules/compile_report.json

python .../scripts/verify_rules_re.py \
  ${OPENCLAW_WORKSPACE}/rules/_candidate_rules_re.json

# 确认无误后：候选 → 正式，再清理中间文件
cp ${OPENCLAW_WORKSPACE}/rules/_candidate_rules_re.json ${OPENCLAW_WORKSPACE}/rules/rules_re.json
python .../scripts/cleanup_rules_intermediates.py ${OPENCLAW_WORKSPACE}
```

### Windows (PowerShell)

```powershell
pip install -r $env:OPENCLAW_WORKSPACE\skills\excel-antlr-to-rules-json\requirements.txt

python ...\scripts\excel_to_rules.py `
  path\to\故障规则_patched.xlsx `
  $env:OPENCLAW_WORKSPACE\rules\_candidate_rules_re.json `
  --report $env:OPENCLAW_WORKSPACE\rules\compile_report.json

python ...\scripts\verify_rules_re.py `
  $env:OPENCLAW_WORKSPACE\rules\_candidate_rules_re.json

# 确认无误后
copy $env:OPENCLAW_WORKSPACE\rules\_candidate_rules_re.json $env:OPENCLAW_WORKSPACE\rules\rules_re.json
python ...\scripts\cleanup_rules_intermediates.py $env:OPENCLAW_WORKSPACE
```

可选：按 `rules_re.json` 生成本地 mock 时序（**不入库**，输出目录自定）：

```bash
python .../scripts/generate_mock_fixtures.py \
  ${OPENCLAW_WORKSPACE}/rules/rules_re.json \
  path/to/local/mock_fixtures
```

可选补丁（不覆盖原表；**用完后由 cleanup 删除 `patches.json` 与 `*_patched.xlsx`**）：

```bash
python .../scripts/apply_excel_patch.py \
  ${OPENCLAW_WORKSPACE}/rules/patches.json \
  ${OPENCLAW_WORKSPACE}/skills/故障规则.xlsx
```

## Excel 列（摘要）

详见 `templates/agent-guide.md` §1–§3。

| 列 | 说明 |
|----|------|
| 设备 / 元器件 / 故障名称 | meta |
| 点位 | 与公式一致；恒定用 `prev`，不要 `_last` |
| 触发条件 | ANTLR 输入，详见 agent-guide §2 |
| 有效数据 | `30min内，90%` / `无需设置`（长句如「无需设置诊断时间和延迟时间」也会自动识别） |
| 原因分析 / 专家处理建议 | 可选，原样进 JSON |
