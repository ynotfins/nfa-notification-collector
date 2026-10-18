# Jetpack / AndroidX libraries in use

**Source of truth:** [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml)  
**Date:** 2026-10-02

| Library | Version pin | Capture/delivery role |
|---|---|---|
| Android Gradle Plugin | 9.2.1 | Build |
| Kotlin | 2.3.10 | Language |
| Compose BOM | 2026.06.00 | UI |
| Activity Compose | 1.13.0 | UI host |
| Lifecycle | 2.10.0 | UI/state |
| Navigation Compose | 2.9.8 | UI |
| Room | 3.0.1 | Durable capture + outbox |
| SQLite framework | 2.7.0 | Room backing |
| WorkManager | 2.11.2 | Delivery drain + keep-alive |
| Coroutines | 1.11.0 | Async dispatch |
| Serialization | 1.11.0 | Envelope / config JSON |
| OkHttp | 5.3.0 | HTTPS ingest |

No Flutter dependencies. Version facts for new pins must re-check Arabold first when the gateway is healthy; this pack used the repo lockfile as authority for “what we use,” not as a license to bump.
