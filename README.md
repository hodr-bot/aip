# pia-core

Shared PIA heart used by the Firefox extension and the Android app.
Pure Kotlin, zero platform dependencies: HTTP, storage and clock are
injected ports (`Ports.kt`) so both consumers share the same logic.

## Rules

- Token TTL is 24h, core refreshes after 12h (`Token.kt`).
- Key negotiation mirrors `pia-foss/manual-connections` (get_token, addKey).
- Kill-switch decision is fail-closed (`KillSwitch.kt`):
  extension enforces it for browser traffic, Android system-wide.
- No credentials in git. Ever.

## v0 scope

Token model + refresh policy, region pick, WireGuard conf builder,
kill-switch decision. Live HTTP comes next behind `HttpPort` fakes.

## Layout

Kotlin Multiplatform (`jvm` + `js(IR)`), Kotlin 2.0.21.
Main sources live in `src/commonMain/kotlin/pia/core`,
tests in `src/jvmTest/kotlin/pia/core`. Stdlib only, no Android
target, no karma, no `jsTest` sources.

## Consuming

- Firefox consumes the JS bundle (`jsNodeDevelopmentLibraryDistribution`
  -> `build/dist/js/developmentLibrary/pia-core.js`).
- Android consumes the JVM artifact (`jvmJar` -> `pia-core-jvm.jar`).

## Verify

```sh
gradle jvmTest
gradle jsJar jsNodeDevelopmentLibraryDistribution
node --check build/dist/js/developmentLibrary/pia-core.js
```
