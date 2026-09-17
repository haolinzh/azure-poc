# Spring Boot 3 Hello on AKS

最小 POC：一个 `GET /hello` 接口部署到 Azure AKS。

## 本地运行

```bash
mvn spring-boot:run
curl http://localhost:8080/hello
```

## 部署到 AKS

前置条件：已安装 Docker Desktop（脚本用 `buildx --platform linux/amd64` 交叉编译，适配 AKS 的 amd64 节点）。

```bash
az login
./scripts/deploy-aks.sh
kubectl get svc hello-app   # 拿 EXTERNAL-IP
curl http://<EXTERNAL-IP>/hello
```

## 清理（避免扣费）

```bash
az group delete -n rg-hello-poc --yes --no-wait
```
