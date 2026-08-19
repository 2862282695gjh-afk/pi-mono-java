# CampusClaw Kubernetes 部署

本目录提供 CampusClaw 的单容器 Kubernetes 部署示例。工具元数据和受管控的工具执行由 MateService 负责，Pod 不再运行 Docker sidecar、DinD 或特权容器。

## 文件说明

| 文件 | 说明 |
|------|------|
| `namespace.yaml` | 创建 campusclaw 命名空间 |
| `persistent-volume.yaml` | 本地存储卷配置（代码目录 + 数据目录） |
| `configmap.yaml` | 应用配置和启动脚本 |
| `deployment.yaml` | 主部署文件（单容器 Pod） |
| `service.yaml` | Service 配置（ClusterIP + NodePort） |
| `kustomization.yaml` | Kustomize 配置（可选） |

## 前置要求

1. Kubernetes 集群（已测试 minikube）
2. kubectl 命令行工具
3. Docker 或兼容的镜像构建工具

## 部署步骤

### 1. 构建 CampusClaw 镜像

```bash
docker build -t campusclaw:latest .
```

### 2. 启动 minikube（如未启动）

```bash
minikube start --driver=docker --memory=4096 --cpus=2
```

### 3. 应用配置

```bash
cd modules/k8s

# 创建命名空间
kubectl apply -f namespace.yaml

# 创建存储卷
kubectl apply -f persistent-volume.yaml

# 一次性应用单容器资源
kubectl apply -k .
```

### 4. 验证部署

```bash
# 查看 Pod 状态
kubectl get pods -n campusclaw -w
kubectl logs -n campusclaw deployment/campusclaw -c campusclaw
kubectl exec -n campusclaw -it deployment/campusclaw -c campusclaw -- /bin/sh
```

### 6. 访问服务

```bash
# 方法1: 使用 minikube service
minikube service -n campusclaw campusclaw-nodeport

# 方法2: 使用 kubectl port-forward
kubectl port-forward -n campusclaw svc/campusclaw 8080:8080
```

## 目录挂载说明

| 宿主机路径 | 容器路径 | 说明 |
|-----------|---------|------|
| PVC | `/data` | 应用数据目录 |
| `emptyDir` | `/tmp` | 临时目录 |

## 清理资源

```bash
kubectl delete namespace campusclaw
```
