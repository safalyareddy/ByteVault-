# ADR-0009: Pinned toolchain and dependency versions

**Status:** Accepted, 2026-11-04

| Item | Version | Notes |
|---|---|---|
| Java | 21 (Temurin/OpenJDK) | `maven.compiler.release=21`; build with JDK 21 |
| Spring Boot | 3.5.16 | Manages Spring Kafka, Flyway, Micrometer, Testcontainers versions |
| Maven | 3.9.x | |
| PostgreSQL image | `postgres:16.9-alpine` | |
| Kafka image | `apache/kafka:3.9.1` | KRaft mode |
| JRE image | `eclipse-temurin:21.0.7_6-jre-alpine` | |
| Node.js | 22 LTS | |
| React | 18.3.1 | |
| Vite | 6.3.5 | |
| TypeScript | 5.8.3 | |

Upgrades happen deliberately, one at a time, each with a full test run.
Nothing floats to `latest`.
