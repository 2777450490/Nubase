#!/bin/bash
# Invoked by systemd (nubase.service) as ExecStart.
# Responsibilities: roll in the new jar, gracefully stop the old process, then
# exec the new java process so systemd keeps tracking the PID.
#
# Non-secret release defaults are loaded from ${WORK_DIR}/nubase.runtime.env.
# Server-specific runtime config is then loaded from ${WORK_DIR}/nubase.env
# (NOT in the repo) and exported via `set -a` below, e.g.:
#   - metadata DB:   METADATA_DB_URL / METADATA_DB_USER / METADATA_DB_PASSWORD
#   - tenant provisioning host: POSTGRES_HOST / POSTGRES_PORT
#   - encryption/admin: PGRST_ENCRYPTION_MASTER_KEY / METADATA_SERVICE_ROLE_KEY
#   - object storage: R2_ACCESS_KEY_ID / R2_SECRET_ACCESS_KEY / R2_*
#   - vectors: S3VECTORS_ACCESS_KEY_ID / S3VECTORS_SECRET_ACCESS_KEY / S3VECTORS_*
#   - email: SMTP_HOST / SMTP_USERNAME / SMTP_PASSWORD
#   - redis / LLM keys (optional)
# See nubase.env.example for the full list with defaults.

set -u

WORK_DIR="/root/nubase"
JAR_DIR="${WORK_DIR}/jar"          # staging dir the deploy uploads into
LOG_DIR="${WORK_DIR}/logs"
JVM_DIR="${WORK_DIR}/jvm"
CURRENT_JAR="nubase.jar"
RUNTIME_ENV_FILE="${WORK_DIR}/nubase.runtime.env"
ENV_FILE="${WORK_DIR}/nubase.env"

mkdir -p "${JAR_DIR}" "${LOG_DIR}" "${JVM_DIR}"

# 1. Promote the freshly-uploaded jar into the working dir (if a new one was staged).
if [ -f "${JAR_DIR}/${CURRENT_JAR}" ]; then
    mv "${JAR_DIR}/${CURRENT_JAR}" "${WORK_DIR}/"
    echo "Promoted new jar ${JAR_DIR}/${CURRENT_JAR} -> ${WORK_DIR}/"
else
    echo "No new jar staged in ${JAR_DIR}; restarting current ${WORK_DIR}/${CURRENT_JAR}"
fi

# 2. Gracefully stop the previous instance.
graceful_shutdown() {
    local PID=$1
    echo "Stopping previous instance (PID: ${PID}) with SIGTERM..."
    kill -TERM "${PID}" 2>/dev/null || true
    for _ in $(seq 1 30); do
        ps -p "${PID}" > /dev/null 2>&1 || { echo "Previous instance exited cleanly."; return 0; }
        sleep 2
    done
    echo "Did not exit within 60s; sending SIGKILL."
    kill -9 "${PID}" 2>/dev/null || true
}

OLD_PID=$(pgrep -f "java .*${CURRENT_JAR}" | head -1)
if [ -n "${OLD_PID:-}" ]; then
    graceful_shutdown "${OLD_PID}"
else
    echo "No running java process for ${CURRENT_JAR}."
fi

# 3. Load release-owned defaults first, then server-specific config so operators can override
#    non-secret defaults without copying secrets as part of a deployment.
if [ -f "${RUNTIME_ENV_FILE}" ]; then
    set -a
    # shellcheck disable=SC1090
    . "${RUNTIME_ENV_FILE}"
    set +a
    echo "Loaded runtime defaults from ${RUNTIME_ENV_FILE}"
else
    echo "ERROR: ${RUNTIME_ENV_FILE} not found."
    exit 1
fi

if [ -f "${ENV_FILE}" ]; then
    set -a
    # shellcheck disable=SC1090
    . "${ENV_FILE}"
    set +a
    echo "Loaded env from ${ENV_FILE}"
else
    echo "WARNING: ${ENV_FILE} not found — the app needs PGRST_ENCRYPTION_MASTER_KEY," \
         "METADATA_DB_* and friends to start. See nubase.env.example."
fi

if [ "${SPRING_PROFILES_ACTIVE:-}" != "prod" ]; then
    echo "ERROR: SPRING_PROFILES_ACTIVE must be prod for this production service."
    exit 1
fi

SERVER_PORT="${NUBASE_SERVER_PORT:-9999}"

# 4. Best-effort OOM protection (inherited by the exec'd java).
echo -100 > /proc/$$/oom_score_adj 2>/dev/null || true

# 5. exec the new jar — replaces this script process so systemd tracks java directly.
echo "Starting ${CURRENT_JAR} on port ${SERVER_PORT}..."
exec java -Dfile.encoding=UTF-8 -Duser.dir="${WORK_DIR}" \
     -Dserver.port="${SERVER_PORT}" \
     -Xms1G -Xmx4G \
     -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath="${JVM_DIR}" \
     -jar "${WORK_DIR}/${CURRENT_JAR}" \
     > "${LOG_DIR}/java.log" 2>&1
