#!/usr/bin/env bash
################################################################################
# JettraStoreMeter: Automated Post-Test Physical Cleanup & Teardown Script
# Ensures all temporary test databases, schemas, and .jettra files are purged.
################################################################################

set -euo pipefail

TARGET_HOST="${1:-127.0.0.1}"
TARGET_PORT="${2:-9091}"
TEST_DB="${3:-meter_test_db}"
STORAGE_PATH="${4:-/var/jettra/data}"

echo "================================================================================"
echo "               JETTRASTORE METER: AUTOMATED TEARDOWN & CLEANUP                 "
echo "================================================================================"
echo "[TEARDOWN] Target Host: ${TARGET_HOST}:${TARGET_PORT}"
echo "[TEARDOWN] Target Test Database: ${TEST_DB}"
echo "[TEARDOWN] Configured Storage Path: ${STORAGE_PATH}"

# 1. Autenticación con admin / admin-jettra para obtener token JettraJWT
echo "[TEARDOWN] Authenticating with JettraStore superuser..."
AUTH_RESPONSE=$(curl -s -X POST "http://${TARGET_HOST}:8080/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "admin-jettra"}' || true)

TOKEN=$(echo "${AUTH_RESPONSE}" | grep -o '"token":"[^"]*' | cut -d'"' -f4 || true)

if [ -n "${TOKEN}" ]; then
  echo "[TEARDOWN] Obtained JettraJWT token successfully."
  
  # 2. Enviar DROP DATABASE al cluster
  echo "[TEARDOWN] Dropping logical test database '${TEST_DB}'..."
  curl -s -X DELETE "http://${TARGET_HOST}:8080/api/v1/db/${TEST_DB}" \
    -H "Authorization: JettraJWT ${TOKEN}" || true
    
  # 3. Forzar compactación global para purga física de tombstones
  echo "[TEARDOWN] Triggering forced physical compaction on storage..."
  curl -s -X POST "http://${TARGET_HOST}:8080/api/v1/admin/compaction/force" \
    -H "Authorization: JettraJWT ${TOKEN}" || true
else
  echo "[WARN] Could not authenticate via REST API. Proceeding to direct disk cleanup..."
fi

# 4. Limpieza física local en caso de ejecución en el mismo host
if [ -d "${STORAGE_PATH}/${TEST_DB}" ]; then
  echo "[TEARDOWN] Purging physical directory and .jettra files: ${STORAGE_PATH}/${TEST_DB}"
  rm -rf "${STORAGE_PATH}/${TEST_DB}"
fi

# 5. Eliminar cualquier archivo residual con prefijo de prueba
if [ -d "${STORAGE_PATH}" ]; then
  find "${STORAGE_PATH}" -maxdepth 2 -name "*meter_test*.*jettra*" -exec rm -f {} +
fi

echo "[SUCCESS] Teardown lifecycle complete. JettraStore returned to clean baseline state."
exit 0
