# AIP (PIA backwards) — minimal private client

AIP is a minimal, private PIA (Private Internet Access) client. The name is
"PIA" spelled backwards. It exists because the official apps are slow and
bloated, ship no usable kill-switch on every platform, and churn through
tokens instead of managing the 24 h token lifetime sanely.

## What it is

Three pieces sharing one negotiation heart:

- **`pia-core`** (`src/commonMain`) — pure-Kotlin shared logic: token model
  with 12 h refresh (24 h PIA expiry never hit), region pick, WireGuard
  config builder, fail-closed kill-switch decision. Stdlib only, no platform
  dependencies: HTTP, storage and clock are injected ports (`Ports.kt`).
  Compiled to JVM (Android) and JS (Firefox).
- **Firefox extension** (`extension/firefox`) — browser-only PIA proxy
  consuming the JS bundle. Covers browser traffic only.
- **Android app** (`android-app`, package `fr.hodr.aip`) — system-wide
  WireGuard tunnel via `VpnService` + `GoBackend`, consuming the JVM
  artifact. The only piece that protects the whole device.

## Features

- Login with PIA username/password; token cached and refreshed after 12 h
  (`PiaClient.ensureValidToken`).
- Server list with lowest-latency pick (`ServerCatalog`, `RegionPicker`).
- Per-connection WireGuard key negotiation (`addKey` mirrors
  `pia-foss/manual-connections`).
- Fail-closed kill-switch decision (`KillSwitch.decide`): `BLOCK_ALL` when
  disconnected with kill-switch on, `ALLOW_LAN_ONLY` when off, `ALLOW_ALL`
  only once connected.
- LAN option: `WireGuard.allowedIpCidrs(allowLan)` — `false` routes
  `0.0.0.0/0` + `::/0` through the tunnel; `true` routes the public-IPv4
  CIDR set plus `::/0`, leaving 10/8, 172.16/12 and 192.168/16 local.
- Failure alerts: on `restoreLease` failure or unexpected teardown the
  service posts a high-importance notification on channel `aip-alerts`.
- Credentials and keys live in EncryptedSharedPreferences / Android Keystore;
  nothing secret is logged.

## Repo layout

```
src/commonMain/kotlin/pia/core/   pia-core: Token, PiaClient, Ports,
                                  ServerCatalog, WireGuard, KillSwitch
src/jvmTest/kotlin/pia/core/      headless tests (stdlib + kotlin-test)
android-app/                      Android VpnService app (fr.hodr.aip)
android-adapter/                  Android adapter + its tests
extension/firefox/                Firefox extension (consumes pia-core.js)
docs/                             INSTALL-android.md, INSTALL-firefox.md,
                                  CONSTRAINED-NETWORK.md
```

Kotlin Multiplatform (`jvm` + `js(IR)`), Kotlin 2.0.21. No Android target
in `pia-core`, no karma, no `jsTest` sources.

## Headless test gate

```sh
gradle jvmTest
```

Runs the `pia-core` unit tests on the JVM. Must stay green before any
device or browser testing.

## Android build

Export `ANDROID_HOME` pointing at an SDK with platform 34 and
build-tools 34.0.0, then from the repo root:

```sh
export ANDROID_HOME=/home/hermes/android-sdk
gradle :android-app:assembleDebug
```

Debug APKs (per-ABI splits) land in
`android-app/build/outputs/apk/debug/`. The `preBuild` task rebuilds the
`pia-core` JVM jar first, so the app never embeds a stale core.

## Install

- **Android**: copy the matching split APK to the phone
  (`arm64-v8a` for most modern phones, `armeabi-v7a` for older 32-bit,
  `x86_64` for the emulator), open it, allow "Install unknown apps" when
  asked. Enter PIA credentials, tap Login, Refresh, pick a server, Connect,
  accept the VPN permission dialog. Details: `docs/INSTALL-android.md`.
- **Firefox**: load `extension/firefox` (or the packaged zip) as a temporary
  add-on via `about:debugging#/runtime/this-firefox`, enter credentials,
  pick a server. Details: `docs/INSTALL-firefox.md`.

## Kill-switch + Always-on notes

- **Android**: Settings > Network > VPN > PIA gear icon: enable "Always-on
  VPN" **and** "Block connections without VPN". Android then blocks all
  traffic outside the tunnel, including across reboots. On boot without a
  lease in the intent the service rebuilds one from stored credentials and
  the last server (`restoreLease`); if that fails it posts an `aip-alerts`
  notification and stops so the system can retry.
- **Firefox**: the extension kill-switch covers browser traffic only.
  System-wide traffic is the Android app's job.

## IPv6 never leaves the tunnel

Both modes include `::/0` in the peer AllowedIPs: all IPv6 traffic is
routed inside the WireGuard tunnel. There is no IPv6 split and no IPv6
leak path by construction — the LAN option only carves RFC1918 ranges out
of IPv4.

## LAN option

Default is full tunnel (`allowLan = false`): `0.0.0.0/0` + `::/0`.
With `allowLan = true` (`EXTRA_ALLOW_LAN` on `PiaVpnService`), the peer
AllowedIPs become the public-IPv4 CIDR list from
`WireGuard.allowedIpCidrs(true)` plus `::/0`, so printers, NAS boxes and
other RFC1918 hosts stay reachable directly while everything public —
including all IPv6 — still goes through the tunnel. DNS handling is
unchanged (PIA DNS from the lease).

## No secrets in git. Ever.

Usernames, passwords, tokens and WireGuard keys are runtime state only
(Keystore / extension storage). Never commit credentials, tokens, private
keys, or device logs containing them.
