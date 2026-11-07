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

## Verify

`gradle test`
