# Mate 工具 Schema 字段解析：字符串形态归一化与非法降级策略

> 模块：`modules/coding-agent-cli`（`common/dto`）
> 状态：Implemented
> 日期：2026-09-05
> 决策记录：[ADR-0045](../decisions/0045-mate-tool-schema-string-normalization.html)

## Context（为什么）

实测网关 `tools/query` 返回的 `input_schema` / `output_schema` 是**序列化 JSON 字符串**
（形如 `"{\"type\":\"object\",\"properties\":{...}}"`），且存在驼峰键 `inputSchema` 变体；
而 `ToolInfo` 将两字段声明为 `Map<String,Object>`。双重不匹配（键名 + 值类型）导致
Jackson 绑定失败**静默置 null**，连锁后果：

```
schema 绑定失败 → ListMateTools 传给模型的 inputSchema = {}（空对象兜底）
→ 模型不知道工具参数结构 → CallMateTool 的 args 随机生成
```

既有单测未暴露：夹具中 schema 写的是 JSON 对象形态，恰好绕过真实环境的字符串形态。

## 关键定义

| 术语 | 定义 |
|---|---|
| 字符串形态 | 网关将 schema 序列化为 JSON 字符串返回（当前实测形态） |
| 对象形态 | schema 直接以 JSON 对象返回（TOOL元数据设计.json 草案的目标形态） |
| 非法 schema | 字符串内容不是合法 JSON 对象（如 `not-a-json`） |

## 机制

新增 `SchemaMapDeserializer`（`common/dto`），`ToolInfo.inputSchema/outputSchema` 挂接：

- **字符串形态**：二次反序列化（`readTree` + `convertValue`）为结构化 Map，
  `properties`/`required`/嵌套类型完整保留；
- **对象形态**：直接归一化（兼容网关未来修正为对象返回）；
- **空串 / null / 非对象 JSON（数组、数字）**：归一化为 `null`；
- **非法 JSON 字符串**：`log.warn` 后归一化为 `null`，**不阻断整批元数据解析**；
- **键名兼容**：`@JsonAlias("inputSchema"/"outputSchema")` 兼容驼峰键，与 SNAKE_CASE
  主绑定（`input_schema`）并存。

## 降级行为与影响

非法 schema 归一为 `null` 后的完整传播路径：

```
ToolInfo.inputSchema = null
→ MateToolMeta.inputSchema = null
→ ListMateToolsTool 序列化兜底：item.set("inputSchema", {})（既有行为）
→ 模型收到空结构 schema
→ 模型不知道该工具的参数结构，CallMateTool 的 args 只能猜测
```

**为什么选 null 降级而非抛异常**：一次 `tools/query` 通常返回数十个工具的元数据，
单个工具的坏 schema 抛异常会阻断**整批**解析——其他健康工具随之不可发现，可用性损失远大于
单工具参数未知的损失。可用性优先。

**为什么不保留原始字符串传给模型**：模型收到无法解析的转义字符串比收到空结构更糟——
可能诱导模型把整段字符串当作参数值回填，产生更难排查的错误。

**缓解**：该路径仅在上游 schema 数据损坏时触发。根治是 Mate 平台注册侧填实参数定义
（TOOL元数据设计.json 落地 + 注册校验"properties 不能为空"），届时降级路径几乎不会命中。

## 边界情况

| 情况 | 行为 |
|---|---|
| 字符串形态（合法 JSON 对象） | 二次反序列化，嵌套 properties/required 完整保留 |
| 对象形态 | 直接归一化 |
| 空串 / null / 非对象 JSON（数组、数字） | 归一为 `null` |
| 非法 JSON 字符串 | `log.warn` + 归一为 `null`，不影响同批其他工具 |
| 驼峰键 `inputSchema` | `@JsonAlias` 兼容 |
| 长错误码挤占摘要行 | 见 frontend PR #225（独立前端行为） |

## 契约改动

- **对外 HTTP 契约**：无变化（ListMateTools 输出字段集不变）；
- **内部解析契约**：schema 字段接受字符串/对象双形态 + 驼峰键别名（更宽容，向后兼容）。

## 测试

`HttpMateToolClientTest`：

- `stringEncodedSchemaWithCamelCaseKeysIsNormalized`——驼峰键 + 字符串形态，
  断言 `properties`/`type` 完整归一化；
- `malformedSchemaStringDegradesToNullInsteadOfFailingBatch`——非法字符串降级 null
  且整批解析不失败；
- `snakeCaseStringSchemaPreservesNestedConstraints`——snake_case 主键 + 字符串形态 +
  `required` 数组保留；
- `mixedBatchWithInvalidSchemaIsolatesDamagePerTool`——混合批次（合法 + 非法共存）
  按工具隔离损伤；
- 既有对象形态夹具保持通过。

## 验证

```bash
JAVA_HOME=<JDK21> ./mvnw -pl modules/coding-agent-cli -am test
```

676+ tests 全绿（含本轮新增 4 用例）；`campusclaw/` 镜像同步再生成（dry-run 内容级差异 0）。
