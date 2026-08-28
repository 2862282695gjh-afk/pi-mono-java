# CampusClaw 临时 Docker 与 Kubernetes 资产清理

## 文档信息

| 项目 | 内容 |
|---|---|
| 文档版本 | v1.0 |
| 变更前源码基线 | `origin/main@dee709fc584dd722d2e94eb381338b997659e35a` |
| 实现分支 | `codex/remove-k8s-assets` |
| 适用范围 | 根 Docker 构建文件、根和 `modules` 下 Kubernetes 资产、Kubernetes Spring Profile、关联说明文档 |
| 变更类型 | 架构变化、过期部署资产清理 |
| 决策状态 | Accepted |

## 1. Context

仓库中的根 `Dockerfile`、`k8s/mateservice-deployment.yaml` 和 `modules/k8s/*` 源于本地工具沙箱及其后续单容器收口阶段。当前产品已删除本地 Docker Sandbox、DinD、Hybrid Tool 和相关执行路由，应用的受管控工具执行由 MateService 承担；仓库现阶段也不维护正式 Kubernetes 部署框架。

保留这些文件会继续表达一个并不存在的交付承诺：根 Dockerfile 已引用被删除的 TUI 模块；Kubernetes 清单没有提供当前应用启动所需的 CampusMate 和 GaussDB 配置；健康检查、ConfigMap、存储和多环境清单也与当前源码不一致。继续局部修补会把未来部署框架的架构决策提前固化在历史样例中。

本次目标是删除仓库拥有的临时 Docker/Kubernetes 资产，使源码边界与当前交付范围一致。未来 Kubernetes 框架属于后续独立设计，本次不提出镜像、Chart、Kustomize、Operator、存储、网络或发布平台方案。

## 2. 变更前源码证据

以下观察均来自变更前基线 `origin/main@dee709fc584dd722d2e94eb381338b997659e35a`：

| 观察到的行为 | 源码证据 |
|---|---|
| 根 Dockerfile 构建 `modules/coding-agent-cli`，激活 `k8s` Profile，并复制已经不存在的 TUI POM | `Dockerfile:14-27`、`Dockerfile:43-51` |
| 根 Kubernetes 文件独立声明 MateService Deployment、Service、HPA 和 PDB，但没有进入文档化部署入口 | `k8s/mateservice-deployment.yaml:1-160` |
| `modules/k8s` 通过 Kustomize 组合 Namespace、本地 PV/PVC、ConfigMap、Deployment 和两个 Service | `modules/k8s/kustomization.yaml:1-20` |
| 默认 Deployment 使用 `campusclaw:latest`、`k8s` Profile 和本地数据 PVC | `modules/k8s/deployment.yaml:20-76` |
| Kubernetes Profile 只覆盖日志格式与日志级别 | `modules/coding-agent-cli/src/main/resources/application-k8s.yml:1-14` |
| README 把 `modules/k8s` 列为项目正式结构的一部分 | `README.md:172-187` |
| 历史 Sandbox 清理设计曾把单容器 Docker/Kubernetes 清单作为过渡目标 | `docs/designs/sandbox-cleanup.md:104-119`、`docs/decisions/0015-sandbox-cleanup-tool-manager.html` |

这些内容是变更前的观察事实，不表示未来 Kubernetes 框架应复用其中的资源命名、镜像布局或运行参数。

## 3. 目标设计

清理后的仓库只维护应用源码及其 Maven/JAR 构建入口：

- `./mvnw package -pl :campusclaw-coding-agent -am -DskipTests` 构建服务 JAR。
- `modules/coding-agent-cli/src/main/resources/application.yml` 继续作为应用唯一通用 Spring 配置源。
- 仓库不提供 Docker 镜像构建契约，也不提供 Kubernetes 资源、集群样例或 Kubernetes 专用 Spring Profile。
- 外部已有镜像、容器、Kubernetes 对象和持久卷不会由源码清理自动删除，仍由对应环境的运维流程确认和处理。
- 未来 Kubernetes 框架是 target-only 后续设计；在正式设计和实现合入前，不把任何部署方式描述为现有能力。

## 4. 设计决策

正式决策见 [ADR-0033：移除仓库内临时 Docker 与 Kubernetes 部署资产](../decisions/0033-remove-temporary-docker-kubernetes-assets.html)。

### 4.1 完整删除而不是修补

根 Dockerfile 与两套 Kubernetes 清单共享同一历史来源，且都不属于当前交付范围。只修复 Dockerfile 或只删除某一套清单仍会留下错误的部署所有权，因此选择整体删除。

### 4.2 不保留空 Profile 或占位目录

`application-k8s.yml` 不包含独立业务语义；空的 `k8s` Profile、空目录和“后续补充”占位文件只会形成新的隐式兼容承诺，因此不保留。

### 4.3 后续部署框架独立设计

未来框架需要重新确定镜像供应链、配置和秘密注入、健康检查、存储、扩缩容、网络、安全上下文及发布验证。本次不从历史样例继承这些决策。

## 5. 文件清理矩阵

| 类别 | 删除内容 | 保留或替代 |
|---|---|---|
| 镜像构建 | 根 `Dockerfile` | Maven/JAR 构建命令 |
| 根部署样例 | `k8s/mateservice-deployment.yaml` | 无；未来框架独立设计 |
| Kubernetes 资源 | `modules/k8s/` 全目录 | 无；不保留 Kustomize、kind、minikube 或 Docker Desktop 样例 |
| Spring Profile | `modules/coding-agent-cli/src/main/resources/application-k8s.yml` | 通用 `application.yml` |
| 项目索引 | README 中的 `modules/k8s` 目录项 | 只列当前维护模块 |
| 历史设计 | 原单容器部署仍作为历史决策保留 | 通过本设计和 ADR-0033 明确后续已被取代 |

## 6. 边界情况与影响

- 本次不修改 Java API、HTTP/SSE 契约、数据库对象、MateService 调用协议或前端行为。
- 删除 `application-k8s.yml` 后，外部即使设置 `SPRING_PROFILES_ACTIVE=k8s`，也不会再获得原有的 ECS 日志格式覆盖；未来部署必须显式定义日志配置。
- 删除仓库文件不会删除任何运行中的 Pod、Service、PV、镜像或本地 Docker 数据。这些外部资源需要按环境单独盘点，不在本次源码变更授权范围内。
- `mate-campusclaw` 当前没有这些 Kubernetes 文件的镜像副本；仍需运行镜像同步检查，证明删除模块资源后两侧保持一致。
- 所有删除内容仍可从变更前 Git 基线恢复，但不再作为受维护入口。

## 7. DFX

- **可维护性：** 消除两套互不一致的 Kubernetes 清单、重复的 Docker Desktop 文件和失效 Docker 构建入口。
- **安全性：** 不再分发包含 root 容器、本地绝对路径、固定 NodePort 和不完整配置注入的样例。
- **可移植性：** 避免把个人路径、minikube 节点名和历史镜像名误当成跨环境契约。
- **性能：** 不改变应用运行路径，没有运行时性能影响。

## 8. 契约改动

以下仓库级部署契约被明确移除：

- `docker build -t campusclaw:latest .`
- `campusclaw:latest` 和 `campusclaw/mateservice:latest` 镜像名约定
- `campusclaw` Namespace、Service、NodePort、PV/PVC、HPA 和 PDB 样例
- `k8s` Spring Profile 的日志覆盖

应用 JAR 名称、Maven 构建命令和 `application.yml` 配置契约保持不变。

## 9. 测试与验证

- 搜索源码和维护文档，确认不再引用已删除文件、Kustomize 命令或仓库自建镜像命令。
- 执行 Maven 服务 JAR 构建，确认删除 Kubernetes Profile 不影响资源打包。
- 执行 `scripts/sync-mate-campusclaw.sh --dry-run`，确认公司镜像目录没有同步漂移。
- 校验新增 ADR HTML 结构和文档链接。
- 确认 Markdown 中不存在 Mermaid，执行 `git diff --check`。

本设计没有新增架构关系图；变更只移除仓库的部署资产所有权，不引入需要图示的运行时组件或数据流。

## 10. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.0 | 2026-08-28 | 删除工具沙箱时期遗留的 Docker/Kubernetes 资产，并把未来 Kubernetes 框架划为后续独立设计 |
