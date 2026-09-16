# Spring Boot 3 Hello on AKS

最小 POC：一个 `GET /hello` 接口部署到 Azure AKS。

## 本地运行

```bash
mvn spring-boot:run
curl http://localhost:8080/hello
```

## 部署到 AKS

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
