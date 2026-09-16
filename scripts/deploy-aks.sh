#!/usr/bin/env bash
set -euo pipefail

RESOURCE_GROUP=rg-hello-poc
LOCATION=eastasia
ACR_NAME=acrhellopoc
AKS_NAME=aks-hello-poc

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

az acr build --registry "$ACR_NAME" --image hello-app:latest .
kubectl apply -f k8s/deployment.yaml -f k8s/service.yaml
kubectl rollout status deployment/hello-app --timeout=120s
kubectl get svc hello-app
