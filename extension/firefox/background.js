'use strict';

/* Firefox MV2 background script (plain JS, no bundler).
 *
 * Core bundle namespace shape (inspected head of
 * build/dist/js/developmentLibrary/pia-core.js):
 * - UMD wrapper: AMD via define(), CommonJS via module.exports, otherwise a
 *   browser global populated as globalThis['pia-core'] (after its
 *   'kotlin-kotlin-stdlib' dependency is loaded first).
 * - The factory exports the JS-safe surface: enums, KillSwitch, WireGuard
 *   and WireGuardLease. Time-based types (AuthToken, RegionEndpoint) and the
 *   HttpPort-backed PiaClient stay JVM-only: Kotlin Long is not exportable,
 *   so the background script below keeps its identical 24h/12h behaviour
 *   mirror for the token timing and calls the live core for verdicts.
 * - Expected namespaced shape: PiaClient (JVM-only) with
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
// Public server-list endpoint (mirrors ServerCatalog.SERVER_LIST_URL); not a secret.
var SERVER_LIST_URL = 'https://serverlist.piaservers.net/vpninfo/servers/v6';

var STORAGE_KEYS = {
  username: 'piaUsername',
  password: 'piaPassword',
  tokenValue: 'piaTokenValue',
  tokenIssuedAt: 'piaTokenIssuedAtSec',
  proxyHost: 'piaProxyHost',
  proxyPort: 'piaProxyPort',
  killSwitchEnabled: 'piaKillSwitchEnabled',
  serverId: 'piaServerId'
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
  var bridge = coreValue('JsTimeBridge');
  if (bridge && typeof bridge.tokenRemainingSec === 'function') {
    try {
      return bridge.tokenRemainingSec(issuedAtEpochSec, TOKEN_TTL_SEC, nowEpochSec);
    } catch (coreError) {
      if (typeof console !== 'undefined' && console.warn) {
        console.warn('[pia] core JsTimeBridge failed, using mirror:', coreError);
      }
    }
  }
  return (issuedAtEpochSec + TOKEN_TTL_SEC) - nowEpochSec;
}

function shouldRefreshToken(issuedAtEpochSec, nowEpochSec) {
  var bridge = coreValue('JsTimeBridge');
  if (bridge && typeof bridge.tokenShouldRefresh === 'function') {
    try {
      return bridge.tokenShouldRefresh(issuedAtEpochSec, nowEpochSec, REFRESH_AFTER_SEC);
    } catch (coreError) {
      if (typeof console !== 'undefined' && console.warn) {
        console.warn('[pia] core JsTimeBridge failed, using mirror:', coreError);
      }
    }
  }
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
/* Server choice (extension only, mirrors ServerCatalog behaviour)      */
/* ------------------------------------------------------------------ */

// In-memory cache for the server list; avoids refetching on every connect.
var cachedServerEntries = null;
var cachedServerEntriesAtMs = 0;
var SERVER_LIST_CACHE_TTL_MS = 60 * 60 * 1000; // 1h
var LATENCY_PROBE_TIMEOUT_MS = 4000;

function skipWhitespaceAt(body, from) {
  var cursor = from;
  while (cursor < body.length && /\s/.test(body[cursor])) {
    cursor += 1;
  }
  return cursor;
}

function findBoolFieldValue(body, field) {
  var key = '"' + field + '"';
  var index = body.indexOf(key);
  while (index >= 0) {
    var cursor = skipWhitespaceAt(body, index + key.length);
    if (cursor < body.length && body[cursor] === ':') {
      cursor = skipWhitespaceAt(body, cursor + 1);
      var rest = body.substring(cursor);
      if (rest.indexOf('true') === 0) {
        return true;
      }
      if (rest.indexOf('false') === 0) {
        return false;
      }
      return null;
    }
    index = body.indexOf(key, index + 1);
  }
  return null;
}

// Slice the balanced object starting at [start], strings respected.
function extractBalancedObjectAt(body, start) {
  var index = start;
  var depth = 0;
  var inString = false;
  var escaped = false;
  while (index < body.length) {
    var ch = body[index];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (ch === '\\') {
        escaped = true;
      } else if (ch === '"') {
        inString = false;
      }
    } else if (ch === '"') {
      inString = true;
    } else if (ch === '{') {
      depth += 1;
    } else if (ch === '}') {
      depth -= 1;
      if (depth === 0) {
        return { text: body.substring(start, index + 1), next: index + 1 };
      }
    }
    index += 1;
  }
  return null;
}

// Mirror ServerCatalog.parseRegions: offline regions skipped, id/dns kept.
function parseServerRegions(body) {
  var entries = [];
  var regionsIndex = body.indexOf('"regions"');
  if (regionsIndex < 0) {
    return entries;
  }
  var cursor = skipWhitespaceAt(body, regionsIndex + '"regions"'.length);
  if (cursor >= body.length || body[cursor] !== ':') {
    return entries;
  }
  cursor = skipWhitespaceAt(body, cursor + 1);
  if (cursor >= body.length || body[cursor] !== '[') {
    return entries;
  }
  cursor += 1;
  var guard = 0;
  while (guard < 10000) {
    guard += 1;
    cursor = skipWhitespaceAt(body, cursor);
    if (cursor >= body.length || body[cursor] === ']') {
      break;
    }
    if (body[cursor] === ',') {
      cursor += 1;
      continue;
    }
    if (body[cursor] !== '{') {
      break;
    }
    var region = extractBalancedObjectAt(body, cursor);
    if (!region) {
      break;
    }
    cursor = region.next;
    var offline = findBoolFieldValue(region.text, 'offline');
    if (!offline) {
      var id = findStringField(region.text, 'id');
      var dns = findStringField(region.text, 'dns');
      if (id && dns) {
        entries.push({ id: id, hostname: dns, latencyMs: null });
      }
    }
  }
  return entries;
}

function compareServersByLatency(a, b) {
  var aKnown = (typeof a.latencyMs === 'number');
  var bKnown = (typeof b.latencyMs === 'number');
  if (aKnown && !bKnown) {
    return -1;
  }
  if (!aKnown && bKnown) {
    return 1;
  }
  if (aKnown && bKnown && a.latencyMs !== b.latencyMs) {
    return a.latencyMs - b.latencyMs;
  }
  if (a.id < b.id) {
    return -1;
  }
  if (a.id > b.id) {
    return 1;
  }
  return 0;
}

// Mirror ServerCatalog.pickBest: lowest known latency wins, unknown sorts
// last, ties break on id. Null when the list is empty.
function pickBestServer(servers) {
  if (!servers || !servers.length) {
    return null;
  }
  var ordered = servers.slice().sort(compareServersByLatency);
  return ordered[0];
}

function measureServerLatency(hostname, timeoutMs) {
  var timeout = timeoutMs || LATENCY_PROBE_TIMEOUT_MS;
  var started = Date.now();
  var url = 'https://' + hostname + '/';
  var finishOk = function () { return Date.now() - started; };
  try {
    if (typeof AbortController !== 'undefined') {
      var controller = new AbortController();
      var timer = setTimeout(function () {
        try { controller.abort(); } catch (abortError) { /* ignore */ }
      }, timeout);
      return fetch(url, {
        mode: 'no-cors',
        cache: 'no-store',
        redirect: 'manual',
        signal: controller.signal
      }).then(function () {
        clearTimeout(timer);
        return finishOk();
      }, function () {
        clearTimeout(timer);
        return null;
      });
    }
  } catch (probeError) {
    return Promise.resolve(null);
  }
  var fetchAttempt = null;
  try {
    fetchAttempt = fetch(url, { mode: 'no-cors', cache: 'no-store', redirect: 'manual' });
  } catch (fetchError) {
    return Promise.resolve(null);
  }
  var timeoutPromise = new Promise(function (resolve) {
    setTimeout(function () { resolve(null); }, timeout);
  });
  return Promise.race([
    fetchAttempt.then(function () { return finishOk(); }, function () { return null; }),
    timeoutPromise
  ]);
}

function annotateServersWithLatency(servers) {
  return Promise.all(servers.map(function (entry) {
    return measureServerLatency(entry.hostname).then(function (latency) {
      return { id: entry.id, hostname: entry.hostname, latencyMs: latency };
    });
  })).then(function (probed) {
    return probed.sort(compareServersByLatency);
  });
}

function fetchServerEntriesRaw() {
  var nowMs = Date.now();
  if (cachedServerEntries && (nowMs - cachedServerEntriesAtMs) < SERVER_LIST_CACHE_TTL_MS) {
    return Promise.resolve(cachedServerEntries.slice());
  }
  return fetch(SERVER_LIST_URL).then(function (response) {
    if (!response.ok) {
      throw new Error('server list request failed with status ' + response.status);
    }
    return response.text();
  }).then(function (body) {
    // First line is the JSON document; trailing lines are the backdrop
    // signature (verified by the platforms, ignored here).
    var firstLine = body.split('\n')[0];
    var entries = parseServerRegions(firstLine);
    if (!entries.length) {
      throw new Error('server list held no regions');
    }
    cachedServerEntries = entries;
    cachedServerEntriesAtMs = Date.now();
    return entries.slice();
  });
}

function fetchServersForPopup() {
  return fetchServerEntriesRaw().then(function (entries) {
    return annotateServersWithLatency(entries);
  });
}

function loadSelectedServerId() {
  return browser.storage.local.get([STORAGE_KEYS.serverId]).then(function (stored) {
    var value = stored[STORAGE_KEYS.serverId] || null;
    return (typeof value === 'string' && value) ? value : null;
  });
}

function saveSelectedServerId(serverId) {
  var update = {};
  update[STORAGE_KEYS.serverId] = serverId;
  return browser.storage.local.set(update).then(function () {
    return serverId;
  });
}

// Honour the stored server choice: map the chosen id to its hostname for
// the next connect. When nothing is stored yet, default to the best pick
// and persist it. Failures fall back to the configured host.
function resolveActiveProxyHost(fallbackHost) {
  return loadSelectedServerId().then(function (storedId) {
    return fetchServerEntriesRaw().then(function (servers) {
      if (!servers || !servers.length) {
        return { proxyHost: fallbackHost, serverId: storedId };
      }
      if (storedId) {
        var match = null;
        for (var i = 0; i < servers.length; i += 1) {
          if (servers[i].id === storedId) {
            match = servers[i];
            break;
          }
        }
        if (match) {
          return { proxyHost: match.hostname, serverId: storedId };
        }
        return { proxyHost: fallbackHost, serverId: storedId };
      }
      var best = pickBestServer(servers);
      if (best) {
        saveSelectedServerId(best.id);
        return { proxyHost: best.hostname, serverId: best.id };
      }
      return { proxyHost: fallbackHost, serverId: null };
    }, function () {
      return { proxyHost: fallbackHost, serverId: storedId };
    });
  }, function () {
    return { proxyHost: fallbackHost, serverId: null };
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
      return resolveActiveProxyHost(settings.proxyHost);
    })
    .then(function (active) {
      settingsHolder.proxyHost = active.proxyHost;
      settingsHolder.serverId = active.serverId;
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

/* ------------------------------------------------------------------ */
/* Popup bridge: server list served through the background script      */
/* ------------------------------------------------------------------ */

function handlePopupMessage(message) {
  if (!message || typeof message.type !== 'string') {
    return Promise.reject(new Error('unrecognised message'));
  }
  if (message.type === 'pia-get-servers') {
    return fetchServersForPopup().then(function (servers) {
      return loadSelectedServerId().then(function (selectedId) {
        return { servers: servers, selectedServerId: selectedId };
      });
    });
  }
  if (message.type === 'pia-get-selected-server') {
    return loadSelectedServerId().then(function (selectedId) {
      return { selectedServerId: selectedId };
    });
  }
  if (message.type === 'pia-set-selected-server') {
    var serverId = message.serverId;
    if (typeof serverId !== 'string' || !serverId) {
      return Promise.reject(new Error('missing server id'));
    }
    return fetchServerEntriesRaw().then(function (servers) {
      var known = false;
      for (var i = 0; i < servers.length; i += 1) {
        if (servers[i].id === serverId) {
          known = true;
          break;
        }
      }
      if (!known) {
        throw new Error('unknown server id');
      }
      return saveSelectedServerId(serverId);
    }).then(function (saved) {
      refreshTokenAndApplyProxy();
      return { selectedServerId: saved };
    });
  }
  return Promise.reject(new Error('unrecognised message'));
}

if (typeof browser !== 'undefined' && browser.runtime && browser.runtime.onMessage) {
  browser.runtime.onMessage.addListener(function (message) {
    return handlePopupMessage(message);
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
