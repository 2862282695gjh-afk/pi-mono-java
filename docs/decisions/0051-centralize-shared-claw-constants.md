# ADR-0051：通过底层 common 模块集中共享业务常量

- Status：Accepted
- Date：2026-09-04
- 文档版本：1.0.0
- 决策类别：架构调整
- 变更前源码基线：`ee3fdb4893228045f06b9b1d1b3b3bb505812c73`

## Context

基线采用分领域常量类，SkillConstants、ResourceIdentifierPatterns、RuntimeApiConstants 和
MateCredentialHeaders 位于 coding-agent-cli，用户目录固定名称位于 ai。用户确认使用一个
ClawConstants 文件，并要求 common 与 ai、codingagent 同级。源码路径、符号和保留项清单见
[共享常量设计](../designs/shared-constants.md)。

仅调整 Java 包名不能改变 Maven 可见性：底层 ai 无法依赖最终服务模块获取常量，因此需要独立 common 模块。

## Decision

新增 `modules/common`，定义 `com.campusclaw.common.constant.ClawConstants`，文件内用 Home、
Agent、Tool、Session、Skill、Runtime、RuntimeApi、Mate 静态分组维护共享常量及对应预编译 Pattern。
直接消费者显式依赖 common，common 不依赖业务模块。所有消费者一次迁移，删除旧类和字段，不提供别名。
私有实现细节、枚举和类型化实例、单例及部署配置保留原职责。

本决策替代 [ADR-0050](0050-unify-skill-name-validation.md) 中将常量放入独立 SkillConstants 文件的
归属决定；ADR-0050 的严格名称约束仍有效。同步修改仓库规则、模块依赖图和镜像模块清单。

## 选项与取舍

| 选项 | 收益 | 代价与决定 |
|---|---|---|
| 继续分领域文件 | 文件较小，各领域单独修改 | 不满足用户确认的单一维护入口；不采用。 |
| 同一文件所有字段平铺 | 集中查找 | 缺少领域分组，字段含义及命名容易混淆；不采用。 |
| ClawConstants 内按领域分组，并置于独立 common | 单文件维护、归属明确、底层模块可依赖 | 增加一个构建模块，文件会增长；采用。 |

## Consequences

项目从四个变为五个主模块，镜像仍是独立生成的单模块。内部 Java 引用位置改变，HTTP 路径、
标识格式、配置键、目录布局和运行行为不变。单文件的协作冲突风险通过明确分组和小范围修改控制。
生产常量不依赖 DTO、Controller 或业务服务，避免循环依赖。

## 验证与关联

验证要求和来源映射见[共享常量设计](../designs/shared-constants.md)及
[模块架构](../module-architecture.md)。镜像同步回归必须检查 common 源码被正确转换和复制。

## 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| 1.0.0 | 2026-09-04 | 按用户确认建立 ClawConstants 单文件及底层 common 模块。 |
