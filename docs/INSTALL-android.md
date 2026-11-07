# Install the Android app

## APK

Debug builds live in `android-app/build/outputs/apk/debug/` (ABI splits):

- `android-app-arm64-v8a-debug.apk` — most modern phones (4.7 MB)
- `android-app-armeabi-v7a-debug.apk` — older 32-bit phones
- `android-app-x86_64-debug.apk` — emulator

Copy the matching APK to the phone and open it. Allow "Install unknown
apps" for the file manager or browser when asked.

## First run

1. Enter the PIA username and password, tap Login. The token (24 h)
   is stored in EncryptedSharedPreferences; credentials stay in the
   Android Keystore-backed store, never in logs.
2. Tap Refresh, pick a server from the list (lowest ping first).
3. Tap Connect and accept the VPN permission dialog.
4. The WireGuard tunnel comes up; Disconnect tears it down.

The token refreshes itself after 12 h on the next Connect
(`PiaClient.ensureValidToken`). The WireGuard key persists in the
Keystore.

## Kill-switch

Settings > Network > VPN > PIA gear icon: enable "Always-on VPN" and
"Block connections without VPN". Android then blocks all traffic
outside the tunnel, including on reboot.

## Build from source

Export `ANDROID_HOME` to the Android SDK (platform 34, build-tools
34.0.0), then from the repo root:

```
gradle :android-app:assembleDebug
```
