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
  proxyUsername: 'piaProxyUsername',
  proxyPassword: 'piaProxyPassword',
  tokenValue: 'piaTokenValue',
  tokenIssuedAt: 'piaTokenIssuedAtSec',
  proxyHost: 'piaProxyHost',
  proxyPort: 'piaProxyPort',
  killSwitchEnabled: 'piaKillSwitchEnabled',
  serverId: 'piaServerId'
};

// Last verdict applied through applyProxyForVerdict (single choke point),
// so the popup status page always reports the real proxy state.
var lastVerdict = 'UNKNOWN';

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

// PIA's SOCKS proxy needs its own generated credentials (the "x" username
// from the Client Control Panel), which differ from the account login used
// for the token API. Stored separately; the account login is the fallback.
var cachedProxyCredentials = null;
var proxyCredentialsInitialised = false;

// Host currently served by the proxy config; the auth provider only answers
// challenges from this host while the proxy is active.
var activeProxyHost = null;

function readProxyCredentialsOnce() {
  if (proxyCredentialsInitialised) {
    return Promise.resolve(cachedProxyCredentials);
  }
  return browser.storage.local.get([
    STORAGE_KEYS.proxyUsername,
    STORAGE_KEYS.proxyPassword,
    STORAGE_KEYS.username,
    STORAGE_KEYS.password
  ]).then(function (stored) {
    var username = stored[STORAGE_KEYS.proxyUsername] ||
      stored[STORAGE_KEYS.username] || null;
    var password = stored[STORAGE_KEYS.proxyPassword] ||
      stored[STORAGE_KEYS.password] || null;
    cachedProxyCredentials = { username: username, password: password };
    proxyCredentialsInitialised = true;
    return cachedProxyCredentials;
  });
}

function provideProxyAuthCredentials(details) {
  if (lastVerdict !== 'ALLOW_ALL' || !details || details.isProxy !== true) {
    return {};
  }
  var challenger = details.challenger || {};
  if (activeProxyHost && challenger.host && challenger.host !== activeProxyHost) {
    return {};
  }
  var creds = cachedProxyCredentials;
  if (!creds || !creds.username || !creds.password) {
    return {};
  }
  return {
    authCredentials: { username: creds.username, password: creds.password }
  };
}

if (typeof browser !== 'undefined' && browser.webRequest && browser.webRequest.onAuthRequired) {
  browser.webRequest.onAuthRequired.addListener(
    provideProxyAuthCredentials,
    { urls: ['<all_urls>'] },
    ['blocking']
  );
}

// Drop the credential caches when the popup saves new values so the next
// connect uses them without a background restart.
if (typeof browser !== 'undefined' && browser.storage && browser.storage.onChanged) {
  browser.storage.onChanged.addListener(function (changes, areaName) {
    if (areaName !== 'local') {
      return;
    }
    if ((changes && STORAGE_KEYS.username in changes) ||
        (changes && STORAGE_KEYS.password in changes)) {
      cachedCredentials = null;
      credentialsInitialised = false;
    }
    if ((changes && STORAGE_KEYS.proxyUsername in changes) ||
        (changes && STORAGE_KEYS.proxyPassword in changes) ||
        (changes && STORAGE_KEYS.username in changes) ||
        (changes && STORAGE_KEYS.password in changes)) {
      cachedProxyCredentials = null;
      proxyCredentialsInitialised = false;
    }
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

// Mirror ServerCatalog.parseRegions: offline regions skipped; keeps the
// pretty name, country code and geo/streaming flags for the popup list.
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
        entries.push({
          id: id,
          hostname: dns,
          latencyMs: null,
          displayName: findStringField(region.text, 'name') || id,
          country: findStringField(region.text, 'country') || '',
          virtual: findBoolFieldValue(region.text, 'geo') === true,
          streaming: id.slice(-3) === '-so'
        });
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
  // PIA endpoints refuse HTTP(S) outright, so a *fast* failure still
  // measures the round trip (TCP refused ~= RTT). Only a full timeout
  // means unmeasurable. Without this every probe reads null and the
  // "fastest server" pick degrades to alphabetical order.
  var finishFailed = function () {
    var elapsed = Date.now() - started;
    return (elapsed < timeout) ? elapsed : null;
  };
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
        return finishFailed();
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
    fetchAttempt.then(function () { return finishOk(); }, function () { return finishFailed(); }),
    timeoutPromise
  ]);
}

function annotateServersWithLatency(servers) {
  return Promise.all(servers.map(function (entry) {
    return measureServerLatency(entry.hostname).then(function (latency) {
      return {
        id: entry.id,
        hostname: entry.hostname,
        latencyMs: latency,
        displayName: entry.displayName,
        country: entry.country,
        virtual: entry.virtual,
        streaming: entry.streaming
      };
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
  lastVerdict = verdict;
  if (verdict === 'ALLOW_ALL') {
    // PIA serves a SOCKS5-only proxy on 1080 (no HTTP CONNECT): route via
    // socks with remote DNS, and let the onAuthRequired provider below feed
    // the generated proxy credentials to Firefox's auth dialog silently.
    activeProxyHost = settings.proxyHost;
    disableFailClosedBlocking();
    return browser.proxy.settings.set({
      value: {
        proxyType: 'manual',
        socks: settings.proxyHost + ':' + settings.proxyPort,
        socksVersion: 5,
        proxyDNS: true,
        passthrough: LAN_BYPASS_LIST.join(', ')
      }
    });
  }
  if (verdict === 'ALLOW_LAN_ONLY') {
    // Direct connection: local addresses stay reachable, tunnel unset.
    activeProxyHost = null;
    disableFailClosedBlocking();
    return browser.proxy.settings.set({ value: { proxyType: 'none' } });
  }
  // BLOCK_ALL or anything unrecognised: fail closed. The webRequest guard
  // goes up FIRST so a proxy.settings failure (e.g. missing private-window
  // permission) still leaves the browser blocked, never silently direct.
  activeProxyHost = null;
  enableFailClosedBlocking();
  return browser.proxy.settings.set({ value: { proxyType: 'none' } })
    .then(null, function (proxyError) {
      if (typeof console !== 'undefined' && console.warn) {
        console.warn('[pia] proxy.settings.set failed; fail-closed guard stays on:', proxyError);
      }
    });
}

/* ------------------------------------------------------------------ */
/* Refresh cycle (alarms, 12h period)                                  */
/* ------------------------------------------------------------------ */

function refreshTokenAndApplyProxy() {
  // Always re-read credentials fresh: the popup (or a test seed) may have
  // written new values after the last read, and a stale cache here would
  // silently keep the extension logged out or unauthenticated.
  cachedCredentials = null;
  credentialsInitialised = false;
  cachedProxyCredentials = null;
  proxyCredentialsInitialised = false;
  var settingsHolder = {};
  return readExtensionSettings()
    .then(function (settings) {
      settingsHolder = settings;
      return resolveActiveProxyHost(settings.proxyHost);
    })
    .then(function (active) {
      settingsHolder.proxyHost = active.proxyHost;
      settingsHolder.serverId = active.serverId;
      return Promise.all([readCredentialsOnce(), loadStoredToken(), readProxyCredentialsOnce()]);
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
  if (message.type === 'pia-get-status') {
    return getExtensionStatus();
  }
  if (message.type === 'pia-set-killswitch') {
    return setKillSwitchEnabled(message.enabled !== false);
  }
  if (message.type === 'pia-reconnect') {
    return refreshTokenAndApplyProxy().then(function () {
      return getExtensionStatus();
    });
  }
  return Promise.reject(new Error('unrecognised message'));
}

/* Status + kill-switch control for the popup pages. */

function getExtensionStatus() {
  return Promise.all([
    readExtensionSettings(),
    loadSelectedServerId(),
    readCredentialsOnce(),
    readProxyCredentialsOnce(),
    loadStoredToken()
  ]).then(function (parts) {
    var settings = parts[0];
    var serverId = parts[1];
    var credentials = parts[2];
    var proxyCreds = parts[3];
    var token = parts[4];
    var remaining = null;
    if (token && typeof token.issuedAtEpochSec === 'number') {
      remaining = remainingSec(token.issuedAtEpochSec, nowEpochSec());
    }
    return {
      proxyActive: lastVerdict === 'ALLOW_ALL',
      blocked: lastVerdict === 'BLOCK_ALL',
      directLanOnly: lastVerdict === 'ALLOW_LAN_ONLY',
      killSwitchEnabled: settings.killSwitchEnabled,
      serverId: serverId || settings.serverId || null,
      proxyHost: settings.proxyHost,
      proxyPort: settings.proxyPort,
      hasCredentials: !!(credentials.username && credentials.password),
      hasProxyCredentials: !!(proxyCreds.username && proxyCreds.password),
      tokenRemainingSec: remaining
    };
  });
}

function setKillSwitchEnabled(enabled) {
  var update = {};
  update[STORAGE_KEYS.killSwitchEnabled] = enabled !== false;
  return browser.storage.local.set(update).then(function () {
    // The storage watcher also triggers a refresh; run one directly so
    // the popup gets the fresh status without waiting for it.
    return refreshTokenAndApplyProxy().then(function () {
      return getExtensionStatus();
    }, function () {
      return getExtensionStatus();
    });
  });
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
