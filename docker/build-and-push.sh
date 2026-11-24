#!/usr/bin/env bash
# ==============================================================================
# Script de Construcción y Publicación de Imagen Limpia de JettraStore en Docker Hub
# (RF-18: Sin bases de datos de ejemplo ni datos semilla precargados)
# ==============================================================================
set -euo pipefail

DOCKER_USER="${DOCKER_USER:-jettra}"
IMAGE_NAME="${IMAGE_NAME:-jettrastore}"
TAG_VERSION="${TAG_VERSION:-1.0}"
PUSH="${PUSH:-false}"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=============================================================================="
echo " JettraStore - Generación de Imagen Docker Limpia para Docker Hub"
echo " Repositorio:   ${DOCKER_USER}/${IMAGE_NAME}"
echo " Tags:          ${TAG_VERSION}, latest"
echo " Plataformas:   ${PLATFORMS}"
echo " Muestras DB:   DESHABILITADAS POR DEFECTO (Clean Image)"
echo " Modo Push:     ${PUSH}"
echo "=============================================================================="

echo "==> 1. Compilando y empaquetando JAR de JettraStore..."
mvn -f "$REPO_ROOT/JettraStore/pom.xml" clean package -DskipTests

echo "==> 2. Construyendo imagen Docker limpia..."
if [ "$PUSH" = "true" ]; then
    echo "==> Publicando en Docker Hub con buildx (multi-plataforma)..."
    docker buildx build \
        --platform "$PLATFORMS" \
        -t "${DOCKER_USER}/${IMAGE_NAME}:${TAG_VERSION}" \
        -t "${DOCKER_USER}/${IMAGE_NAME}:latest" \
        -f "$REPO_ROOT/JettraStore/Dockerfile" \
        "$REPO_ROOT/JettraStore" \
        --push
    echo "✅ Imagen ${DOCKER_USER}/${IMAGE_NAME}:${TAG_VERSION} y latest publicada exitosamente en Docker Hub."
else
    echo "==> Construcción local (para subir a Docker Hub ejecutar con: PUSH=true $0)..."
    docker build \
        -t "${DOCKER_USER}/${IMAGE_NAME}:${TAG_VERSION}" \
        -t "${DOCKER_USER}/${IMAGE_NAME}:latest" \
        -f "$REPO_ROOT/JettraStore/Dockerfile" \
        "$REPO_ROOT/JettraStore"
    echo "✅ Imagen construida localmente como ${DOCKER_USER}/${IMAGE_NAME}:${TAG_VERSION} y ${DOCKER_USER}/${IMAGE_NAME}:latest."
fi
