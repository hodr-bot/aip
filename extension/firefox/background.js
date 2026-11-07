'use strict';

/* Firefox MV2 background script (plain JS, no bundler).
 *
 * Core bundle namespace shape (inspected head of
 * build/dist/js/developmentLibrary/pia-core.js):
 * - UMD wrapper: AMD via define(), CommonJS via module.exports, otherwise a
 *   browser global populated as globalThis['pia-core'] (after its
 *   'kotlin-kotlin-stdlib' dependency is loaded first).
 * - The factory currently returns an EMPTY namespace object: no JsExport, so
 *   Object.keys(require('pia-core.js')) is [] and none of the Kotlin
 *   declarations (pia.core.PiaClient / KillSwitch / AuthToken / TokenStore /
 *   HttpPort / Clock / RegionPicker / WireGuard) are reachable from JS yet.
 * - Expected namespaced shape once @JsExport lands: pia.core.PiaClient with
 *   login()/ensureValidToken()/addKey(), pia.core.KillSwitch.decide(state,
 *   killSwitchEnabled) returning ALLOW_ALL / ALLOW_LAN_ONLY / BLOCK_ALL,
 *   pia.core.AuthToken with 24h TTL refreshed after 12h (Token.kt).
 *
 * This script therefore resolves the core namespace defensively and falls
 * back to a local mirror with IDENTICAL behaviour (24h TTL, 12h refresh,
 * fail-closed verdict) until the bundle exports its symbols. When exports
 * land, the core path is favoured automatically with no further changes.
 *
 * Packaged layout (MV2, background scripts cannot use import/export):
 *   extension/firefox/background.js
 *   extension/firefox/vendor/kotlin-kotlin-stdlib.js  (copied from
 *     build/dist/js/developmentLibrary/kotlin-kotlin-stdlib.js)
 *   extension/firefox/vendor/pia-core.js              (copied from
 *     build/dist/js/developmentLibrary/pia-core.js)
 * Manifest needs: alarms, storage, proxy, webRequest, webRequestBlocking,
 *   "<all_urls>" (blocking listener) plus host permission for the PIA API.
 *
 * Identifiers favour en-GB spelling throughout. No secrets live here: the
 * PIA username and password are read once from extension storage and never
 * hardcoded.
 */

try {
  importScripts('vendor/kotlin-kotlin-stdlib.js', 'vendor/pia-core.js');
} catch (importError) {
  // Bundle not yet packaged beside this script (or running under node
  // --check where importScripts is undefined): carry on with the local
  // behaviour mirror. The failure is logged once at startup.
  if (typeof console !== 'undefined' && console.warn) {
    console.warn('[pia] core bundle unavailable, using behaviour mirror:', importError);
  }
}

var PIA_ALARM_NAME = 'pia-token-refresh';
var REFRESH_PERIOD_MINUTES = 12 * 60; // 12h, mirrors AuthToken.REFRESH_AFTER_SEC
var TOKEN_TTL_SEC = 24 * 60 * 60; // 24h, mirrors AuthToken.TOKEN_TTL_SEC
var REFRESH_AFTER_SEC = 12 * 60 * 60; // 12h, mirrors AuthToken.REFRESH_AFTER_SEC
// Public API endpoint (mirrors PiaClient.Companion.TOKEN_URL); not a secret.
var TOKEN_URL = 'https://www.privateinternetaccess.com/api/client/v2/token';

var STORAGE_KEYS = {
  username: 'piaUsername',
  password: 'piaPassword',
  tokenValue: 'piaTokenValue',
  tokenIssuedAt: 'piaTokenIssuedAtSec',
  proxyHost: 'piaProxyHost',
  proxyPort: 'piaProxyPort',
  killSwitchEnabled: 'piaKillSwitchEnabled'
};

// Non-secret packaging defaults; the user may override them via storage.
var DEFAULT_PROXY_HOST = 'proxy-nl.privateinternetaccess.com';
var DEFAULT_PROXY_PORT = 1080;
var LAN_BYPASS_LIST = ['10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'];

/* ------------------------------------------------------------------ */
/* Core namespace resolution                                           */
/* ------------------------------------------------------------------ */

function resolveCoreNamespace() {
  var holder = (typeof globalThis !== 'undefined') ? globalThis : this;
  var namespace = (holder && holder['pia-core']) || {};
  // Accept either a flat export or a nested pia.core shape.
  var pia = namespace.pia || namespace['pia.core'] || namespace;
  var core = pia.core || pia;
  return { root: namespace, pia: pia, core: core };
}

var CORE = resolveCoreNamespace();

function coreValue(name) {
  if (CORE.core && CORE.core[name]) {
    return CORE.core[name];
  }
  if (CORE.root && CORE.root[name]) {
    return CORE.root[name];
  }
  return null;
}

/* ------------------------------------------------------------------ */
/* Behaviour mirror (used only while the bundle exports nothing)       */
/* ------------------------------------------------------------------ */

function remainingSec(issuedAtEpochSec, nowEpochSec) {
  return (issuedAtEpochSec + TOKEN_TTL_SEC) - nowEpochSec;
}

function shouldRefreshToken(issuedAtEpochSec, nowEpochSec) {
  return (nowEpochSec - issuedAtEpochSec) >= REFRESH_AFTER_SEC ||
    remainingSec(issuedAtEpochSec, nowEpochSec) <= REFRESH_AFTER_SEC;
}

// Mirrors KillSwitch.kt: fail-closed. CONNECTED -> ALLOW_ALL, otherwise
// BLOCK_ALL when the kill switch is enabled, else ALLOW_LAN_ONLY.
function decideVerdict(connectionState, killSwitchEnabled) {
  if (connectionState === 'CONNECTED') {
    return 'ALLOW_ALL';
  }
  return killSwitchEnabled ? 'BLOCK_ALL' : 'ALLOW_LAN_ONLY';
}

function normaliseVerdict(rawVerdict) {
  if (!rawVerdict) {
    return 'BLOCK_ALL';
  }
  if (typeof rawVerdict === 'string') {
    return rawVerdict;
  }
  // Kotlin enum instance: prefer its name when visible.
  if (typeof rawVerdict.name === 'function') {
    try {
      return String(rawVerdict.name());
    } catch (nameError) {
      return 'BLOCK_ALL';
    }
  }
  if (typeof rawVerdict.name === 'string') {
    return rawVerdict.name;
  }
  return 'BLOCK_ALL';
}

function decideTrafficVerdict(connectionState, killSwitchEnabled) {
  var killSwitch = coreValue('KillSwitch');
  var connectionStates = coreValue('ConnectionState');
  var verdicts = coreValue('TrafficVerdict');
  if (killSwitch && typeof killSwitch.decide === 'function' &&
      connectionStates && verdicts) {
    try {
      var stateEnum = connectionStates[connectionState];
      var raw = killSwitch.decide(stateEnum, killSwitchEnabled);
      return normaliseVerdict(raw && (raw.name || raw));
    } catch (coreError) {
      if (typeof console !== 'undefined' && console.warn) {
        console.warn('[pia] core KillSwitch failed, using mirror:', coreError);
      }
    }
  }
  return decideVerdict(connectionState, killSwitchEnabled);
}

/* ------------------------------------------------------------------ */
/* Minimal JSON field helpers (mirror PiaClient string search)         */
/* ------------------------------------------------------------------ */

function findStringField(body, field) {
  var key = '"' + field + '"';
  var index = body.indexOf(key);
  while (index >= 0) {
    var cursor = index + key.length;
    while (cursor < body.length && /\s/.test(body[cursor])) {
      cursor += 1;
    }
    if (cursor < body.length && body[cursor] === ':') {
      cursor += 1;
      while (cursor < body.length && /\s/.test(body[cursor])) {
        cursor += 1;
      }
      if (cursor < body.length && body[cursor] === '"') {
        var start = cursor + 1;
        var end = body.indexOf('"', start);
        if (end > start) {
          return body.substring(start, end);
        }
        return null;
      }
      return null;
    }
    index = body.indexOf(key, index + 1);
  }
  return null;
}

/* ------------------------------------------------------------------ */
/* Credentials: read once from extension storage, never hardcoded      */
/* ------------------------------------------------------------------ */

var cachedCredentials = null;
var credentialsInitialised = false;

function readCredentialsOnce() {
  if (credentialsInitialised) {
    return Promise.resolve(cachedCredentials);
  }
  return browser.storage.local.get([STORAGE_KEYS.username, STORAGE_KEYS.password])
    .then(function (stored) {
      var username = stored[STORAGE_KEYS.username] || null;
      var password = stored[STORAGE_KEYS.password] || null;
      cachedCredentials = { username: username, password: password };
      credentialsInitialised = true;
      return cachedCredentials;
    });
}

function readExtensionSettings() {
  return browser.storage.local.get([
    STORAGE_KEYS.proxyHost,
    STORAGE_KEYS.proxyPort,
    STORAGE_KEYS.killSwitchEnabled
  ]).then(function (stored) {
    return {
      proxyHost: stored[STORAGE_KEYS.proxyHost] || DEFAULT_PROXY_HOST,
      proxyPort: stored[STORAGE_KEYS.proxyPort] || DEFAULT_PROXY_PORT,
      killSwitchEnabled: (STORAGE_KEYS.killSwitchEnabled in stored) ?
        stored[STORAGE_KEYS.killSwitchEnabled] !== false : true
    };
  });
}

/* ------------------------------------------------------------------ */
/* TokenStore backed by browser.storage.local                          */
/* ------------------------------------------------------------------ */

function loadStoredToken() {
  return browser.storage.local.get([STORAGE_KEYS.tokenValue, STORAGE_KEYS.tokenIssuedAt])
    .then(function (stored) {
      var value = stored[STORAGE_KEYS.tokenValue] || null;
      var issuedAt = stored[STORAGE_KEYS.tokenIssuedAt];
      if (!value || typeof issuedAt !== 'number') {
        return null;
      }
      return { value: value, issuedAtEpochSec: issuedAt, ttlSec: TOKEN_TTL_SEC };
    });
}

function saveStoredToken(token) {
  return browser.storage.local.set({
    piaTokenValue: token.value,
    piaTokenIssuedAtSec: token.issuedAtEpochSec
  });
}

function clearStoredToken() {
  return browser.storage.local.remove([STORAGE_KEYS.tokenValue, STORAGE_KEYS.tokenIssuedAt]);
}

function nowEpochSec() {
  return Math.floor(Date.now() / 1000);
}

/* ------------------------------------------------------------------ */
/* PiaClient wiring: core login when available, mirror otherwise       */
/* ------------------------------------------------------------------ */

function loginThroughCore(username, password) {
  var PiaClient = coreValue('PiaClient');
  if (!PiaClient) {
    return null;
  }
  try {
    // Core HttpPort + Clock seam over fetch / system clock. The current
    // core build is synchronous, while fetch is asynchronous, so this path
    // activates only once the published bundle exposes an async-capable
    // client; until then the mirror below handles login.
    var httpPort = {
      postForm: function () {
        throw new Error('synchronous HttpPort unavailable in background script');
      },
      get: function () {
        throw new Error('synchronous HttpPort unavailable in background script');
      }
    };
    var clock = { nowEpochSec: function () { return nowEpochSec(); } };
    var client = new PiaClient(httpPort, clock);
    if (typeof client.login !== 'function') {
      return null;
    }
    return Promise.resolve(client.login(username, password))
      .then(function (token) {
        var value = (token && (token.value || token.get_value_j01efc_k$)) || null;
        var issued = (token && (token.issuedAtEpochSec || token.issuedAtEpochSec_1)) || null;
        return {
          value: (typeof value === 'function') ? value.call(token) : String(value),
          issuedAtEpochSec: Number((typeof issued === 'function') ? issued.call(token) : issued) || nowEpochSec(),
          ttlSec: TOKEN_TTL_SEC
        };
      });
  } catch (coreError) {
    if (typeof console !== 'undefined' && console.warn) {
      console.warn('[pia] core PiaClient unavailable, using mirror:', coreError);
    }
    return null;
  }
}

function loginViaMirror(username, password) {
  var form = 'username=' + encodeURIComponent(username) +
    '&password=' + encodeURIComponent(password);
  return fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: form
  }).then(function (response) {
    return response.text();
  }).then(function (body) {
    var status = findStringField(body, 'status');
    var token = findStringField(body, 'token');
    if (status !== null && status !== 'OK') {
      throw new Error('login rejected with status: ' + status);
    }
    if (!token) {
      throw new Error('login response held no token');
    }
    return { value: token, issuedAtEpochSec: nowEpochSec(), ttlSec: TOKEN_TTL_SEC };
  });
}

function loginWithFreshToken(username, password) {
  var attempted = loginThroughCore(username, password);
  if (attempted) {
    return attempted.catch(function () {
      return loginViaMirror(username, password);
    });
  }
  return loginViaMirror(username, password);
}

/* ------------------------------------------------------------------ */
/* Proxy + fail-closed enforcement                                     */
/* ------------------------------------------------------------------ */

var blockingListenerRegistered = false;

function blockAllTraffic() {
  return { cancel: true };
}

function enableFailClosedBlocking() {
  if (blockingListenerRegistered) {
    return;
  }
  browser.webRequest.onBeforeRequest.addListener(
    blockAllTraffic,
    { urls: ['<all_urls>'] },
    ['blocking']
  );
  blockingListenerRegistered = true;
}

function disableFailClosedBlocking() {
  if (!blockingListenerRegistered) {
    return;
  }
  browser.webRequest.onBeforeRequest.removeListener(blockAllTraffic);
  blockingListenerRegistered = false;
}

function applyProxyForVerdict(verdict, settings) {
  if (verdict === 'ALLOW_ALL') {
    disableFailClosedBlocking();
    return browser.proxy.settings.set({
      value: {
        proxyType: 'manual',
        http: settings.proxyHost + ':' + settings.proxyPort,
        ssl: settings.proxyHost + ':' + settings.proxyPort,
        socksVersion: 5,
        passthrough: LAN_BYPASS_LIST.join(', ')
      }
    });
  }
  if (verdict === 'ALLOW_LAN_ONLY') {
    // Direct connection: local addresses stay reachable, tunnel unset.
    disableFailClosedBlocking();
    return browser.proxy.settings.set({ value: { proxyType: 'none' } });
  }
  // BLOCK_ALL or anything unrecognised: fail closed. Direct proxy plus a
  // webRequest guard that cancels every request until recovery.
  return browser.proxy.settings.set({ value: { proxyType: 'none' } })
    .then(function () {
      enableFailClosedBlocking();
    });
}

/* ------------------------------------------------------------------ */
/* Refresh cycle (alarms, 12h period)                                  */
/* ------------------------------------------------------------------ */

function refreshTokenAndApplyProxy() {
  var settingsHolder = null;
  return readExtensionSettings()
    .then(function (settings) {
      settingsHolder = settings;
      return Promise.all([readCredentialsOnce(), loadStoredToken()]);
    })
    .then(function (results) {
      var credentials = results[0];
      var cached = results[1];
      var now = nowEpochSec();
      if (cached && !shouldRefreshToken(cached.issuedAtEpochSec, now)) {
        var verdict = decideTrafficVerdict('CONNECTED', settingsHolder.killSwitchEnabled);
        return applyProxyForVerdict(verdict, settingsHolder);
      }
      if (!credentials.username || !credentials.password) {
        var missingVerdict = decideTrafficVerdict('DISCONNECTED', settingsHolder.killSwitchEnabled);
        return applyProxyForVerdict(missingVerdict, settingsHolder).then(function () {
          throw new Error('no stored credentials; staying fail-closed');
        });
      }
      return loginWithFreshToken(credentials.username, credentials.password)
        .then(function (refreshed) {
          return saveStoredToken(refreshed).then(function () {
            var connectedVerdict = decideTrafficVerdict('CONNECTED', settingsHolder.killSwitchEnabled);
            return applyProxyForVerdict(connectedVerdict, settingsHolder);
          });
        });
    })
    .catch(function (refreshError) {
      if (typeof console !== 'undefined' && console.warn) {
        console.warn('[pia] token refresh failed, failing closed:', refreshError);
      }
      return readExtensionSettings().then(function (settings) {
        return applyProxyForVerdict('BLOCK_ALL', settings);
      });
    });
}

function scheduleRefreshAlarm() {
  return browser.alarms.create(PIA_ALARM_NAME, {
    periodInMinutes: REFRESH_PERIOD_MINUTES
  });
}

if (typeof browser !== 'undefined' && browser.alarms) {
  browser.alarms.onAlarm.addListener(function (alarm) {
    if (alarm && alarm.name === PIA_ALARM_NAME) {
      refreshTokenAndApplyProxy();
    }
  });

  if (browser.runtime && browser.runtime.onStartup) {
    browser.runtime.onStartup.addListener(function () {
      scheduleRefreshAlarm();
      refreshTokenAndApplyProxy();
    });
  }

  if (browser.runtime && browser.runtime.onInstalled) {
    browser.runtime.onInstalled.addListener(function () {
      scheduleRefreshAlarm();
      refreshTokenAndApplyProxy();
    });
  }

  scheduleRefreshAlarm();
  refreshTokenAndApplyProxy();
}
