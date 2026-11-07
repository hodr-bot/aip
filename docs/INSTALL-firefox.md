# Install the PIA Core Firefox extension (temporary load)

## What you get

Browser-only PIA proxy with a shared core: 12h token refresh (24h PIA
expiry never hit), per-connection key negotiation, server choice,
fail-closed kill switch for browser traffic.

## Prerequisites

- Firefox 91.1.0 or newer (desktop).
- Your PIA username and password (the regular account credentials, used
  for the token API).
- Your PIA **proxy** username and password: the generated `x…` login from
  the PIA Client Control Panel (separate from the account login).
- The packaged build `dist/pia_core-0.1.0.zip`, or the
  `extension/firefox` source directory.

## Load it

1. Unzip `dist/pia_core-0.1.0.zip` anywhere, or use `extension/firefox`.
2. Open `about:debugging#/runtime/this-firefox`.
3. Click **Load Temporary Add-on** and pick `manifest.json`.
4. Pin the **PIA Core** icon to the toolbar.
5. Open `about:addons` → PIA Core → **Allow in private windows**.
   Firefox only grants the proxy API to extensions with this enabled;
   without it the extension stays fail-closed (blocked, never direct).

## Configure it

1. Click the toolbar icon to open the popup (Status page shows the live
   state: Protected / Blocked / Direct).
2. On the **Account** page enter the PIA username and password, plus the
   generated proxy username (`x…`) and proxy password, then save.
   Both logins are stored in `browser.storage.local` only and never logged.
   Saving immediately reconnects.
3. On the **Servers** page pick a server (lowest measured latency is
   pre-selected). Saving the choice immediately reconnects through it.
4. On the **Kill switch** page, leave it ON to block every connection
   outside the proxy whenever the proxy drops. Turning it OFF lets the
   browser fall back to a direct connection (LAN stays reachable).
5. The connection applies automatically on browser startup and on
   every 12h token refresh. There is no manual on/off switch yet:
   to stop routing browser traffic through PIA, remove the add-on
   or disable it in `about:addons`.

## Behaviour notes

- Tokens refresh every 12h via alarm; a new key is negotiated per
  connection and refreshed on the same schedule.
- The kill switch covers browser traffic only. System-wide traffic is
  the Android app's job (Always-on VPN).
- Temporary add-ons unload on browser restart; repeat the load step
  after each restart. Credentials and server choice persist in
  extension storage.

## Remove it

`about:debugging#/runtime/this-firefox` → **Remove** next to PIA Core,
then clear extension storage if you want no trace left.
