#!/usr/bin/env bash
set -euo pipefail

RESOURCE_GROUP=rg-hello-poc
LOCATION=eastasia
ACR_NAME=acrhellopoc
AKS_NAME=aks-hello-poc
IMAGE="$ACR_NAME.azurecr.io/hello-app:latest"

az group create --name "$RESOURCE_GROUP" --location "$LOCATION"
az acr create --resource-group "$RESOURCE_GROUP" --name "$ACR_NAME" --sku Basic
az aks create \
  --resource-group "$RESOURCE_GROUP" \
  --name "$AKS_NAME" \
  --node-count 1 \
  --node-vm-size Standard_B2s_v2 \
  --enable-managed-identity \
  --generate-ssh-keys
az aks update --resource-group "$RESOURCE_GROUP" --name "$AKS_NAME" --attach-acr "$ACR_NAME"
az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$AKS_NAME" --overwrite-existing

# 免费订阅不支持 az acr build（TasksOperationsNotAllowed），改用本地 buildx 构建。
# AKS 节点是 amd64、本机 Mac 是 arm64，必须指定 --platform linux/amd64，否则报 exec format error。
az acr login --name "$ACR_NAME"
docker buildx build --platform linux/amd64 -t "$IMAGE" --load .
docker push "$IMAGE"

kubectl apply -f k8s/deployment.yaml -f k8s/service.yaml
kubectl rollout status deployment/hello-app --timeout=120s
kubectl get svc hello-app
