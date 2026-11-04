#!/usr/bin/env bash
# Builds both Java services (unit tests included) and the frontend. Needs JDK 21+ and Node 22.
source "$(dirname "$0")/lib.sh"
cd "$ROOT"
mvn -B -ntp package
(cd frontend && npm ci --no-audit --no-fund && npm run build)
