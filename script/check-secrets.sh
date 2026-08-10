#!/usr/bin/env bash
# Pre-release secret scan for Nubase.
#
#   bash script/check-secrets.sh            # scan full git history (what gets published)
#   bash script/check-secrets.sh --staged   # scan only staged changes (fast pre-commit gate)
#
# Exit non-zero if any secret is found, so you can wire it into a pre-commit/pre-push
# hook or just run it before tagging a release. Uses gitleaks with .gitleaks.toml,
# falling back to the official Docker image when gitleaks is not installed.
#
# NOTE: this scans git-tracked/committed content — i.e. exactly what would be open-sourced.
# It deliberately does NOT scan .gitignore'd local files (e.g. script/deploy/nubase.env),
# which are allowed to hold real secrets because they never enter the repo.
set -euo pipefail

cd "$(dirname "$0")/.."
CONFIG=".gitleaks.toml"
MODE="${1:-}"
GITLEAKS_VERSION="8.30.1"

case "$MODE" in
  "")
    GITLEAKS_ARGS=(git --config "$CONFIG" --redact --verbose .)
    echo "🔍 Scanning full git history for secrets…"
    ;;
  --staged)
    GITLEAKS_ARGS=(git --staged --config "$CONFIG" --redact --verbose .)
    echo "🔍 Scanning STAGED changes for secrets…"
    ;;
  *)
    echo "Usage: bash script/check-secrets.sh [--staged]" >&2
    exit 2
    ;;
esac

run() {
  if command -v gitleaks >/dev/null 2>&1; then
    gitleaks "${GITLEAKS_ARGS[@]}"
  elif command -v docker >/dev/null 2>&1; then
    echo "(gitleaks not installed — using zricethezav/gitleaks:v${GITLEAKS_VERSION})"
    docker run --rm -v "$PWD:/repo" -w /repo "zricethezav/gitleaks:v${GITLEAKS_VERSION}" "${GITLEAKS_ARGS[@]}"
  else
    echo "❌ Neither gitleaks nor docker is installed."
    echo "   Install:  brew install gitleaks   (or see https://github.com/gitleaks/gitleaks)"
    exit 2
  fi
}

if run; then
  echo "✅ No secrets found in the scanned scope."
else
  scan_status=$?
  echo ""
  if [[ "$scan_status" -eq 1 ]]; then
    echo "❌ Potential secret(s) detected above. Do NOT publish until resolved."
    echo "   • If it's a real secret: remove it, rotate it, and rewrite history if already committed."
    echo "   • If it's a verified false positive (test fixture/placeholder): add a narrowly scoped allowlist entry to $CONFIG."
  else
    echo "❌ Secret scan failed before producing a trustworthy result (exit $scan_status)."
  fi
  exit "$scan_status"
fi
