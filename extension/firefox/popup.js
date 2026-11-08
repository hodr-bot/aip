'use strict';

/* Popup logic: tabbed pages (Status, Servers, Kill switch, Account) in the
 * same dark style as the Android app (#101410 / #1A211A / #7ED321).
 * The background owns all proxy state; this page only renders it.
 */

var MESSAGE_GET_SERVERS = 'pia-get-servers';
var MESSAGE_SET_SERVER = 'pia-set-selected-server';
var MESSAGE_GET_STATUS = 'pia-get-status';
var MESSAGE_SET_KILLSWITCH = 'pia-set-killswitch';
var MESSAGE_RECONNECT = 'pia-reconnect';

var STORAGE_KEY_USERNAME = 'piaUsername';
var STORAGE_KEY_PASSWORD = 'piaPassword';
var STORAGE_KEY_PROXY_USERNAME = 'piaProxyUsername';
var STORAGE_KEY_PROXY_PASSWORD = 'piaProxyPassword';

var serverListElement = null;
var statusMessageElement = null;
var refreshButtonElement = null;
var credentialsFormElement = null;
var usernameInputElement = null;
var passwordInputElement = null;
var credentialsStatusElement = null;
var proxyUsernameInputElement = null;
var proxyPasswordInputElement = null;
var heroStateElement = null;
var statusServerElement = null;
var statusProxyElement = null;
var statusProxyAuthElement = null;
var statusTokenElement = null;
var statusPageMessageElement = null;
var reconnectButtonElement = null;
var killswitchToggleElement = null;
var killswitchStateElement = null;
var killswitchExplainElement = null;
var killswitchWarnElement = null;
var headerDotElement = null;

var selectedServerId = null;

function formatLatency(latencyMs) {
  if (typeof latencyMs !== 'number') {
    return 'latency unknown';
  }
  return Math.round(latencyMs) + ' ms';
}

function formatTokenRemaining(totalSeconds) {
  if (typeof totalSeconds !== 'number' || totalSeconds <= 0) {
    return 'expired';
  }
  var hours = Math.floor(totalSeconds / 3600);
  if (hours >= 1) {
    return 'valid ' + hours + 'h';
  }
  var minutes = Math.floor(totalSeconds / 60);
  return 'valid ' + minutes + 'm';
}

function showStatusMessage(text) {
  if (statusMessageElement) {
    statusMessageElement.textContent = text;
  }
}

function clearOrganisedList() {
  if (serverListElement) {
    while (serverListElement.firstChild) {
      serverListElement.removeChild(serverListElement.firstChild);
    }
  }
}

// Sort helper shared with ServerCatalog: lowest known latency first,
// unmeasured last.
var SVG_NS = 'http://www.w3.org/2000/svg';
// Inline vector icons, no emoji: cloud outline for virtual locations,
// play triangle for streaming-optimized regions.
function makeTagIcon(kind, label) {
  var svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('class', 'tag-icon');
  svg.setAttribute('aria-hidden', 'true');
  var title = document.createElementNS(SVG_NS, 'title');
  title.textContent = label;
  svg.appendChild(title);
  var path = document.createElementNS(SVG_NS, 'path');
  if (kind === 'virtual') {
    svg.setAttribute('fill', 'none');
    svg.setAttribute('stroke', 'currentColor');
    svg.setAttribute('stroke-width', '2');
    svg.setAttribute('stroke-linecap', 'round');
    svg.setAttribute('stroke-linejoin', 'round');
    path.setAttribute('d', 'M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z');
  } else {
    svg.setAttribute('fill', 'currentColor');
    path.setAttribute('d', 'M8 5v14l11-7z');
  }
  svg.appendChild(path);
  return svg;
}
function compareLatencyThenTitle(left, right) {
  var leftKnown = (typeof left.latencyMs === 'number');
  var rightKnown = (typeof right.latencyMs === 'number');
  if (leftKnown && !rightKnown) {
    return -1;
  }
  if (!leftKnown && rightKnown) {
    return 1;
  }
  if (leftKnown && rightKnown && left.latencyMs !== right.latencyMs) {
    return left.latencyMs - right.latencyMs;
  }
  if (left.displayTitle < right.displayTitle) {
    return -1;
  }
  if (left.displayTitle > right.displayTitle) {
    return 1;
  }
  return 0;
}

function orderServersByLatency(servers) {
  return servers.slice().sort(compareLatencyThenTitle);
}

function decorateServerEntry(server) {
  return {
    id: server.id,
    hostname: server.hostname,
    latencyMs: (typeof server.latencyMs === 'number') ? server.latencyMs : null,
    displayTitle: server.displayName || server.id,
    country: server.country || '',
    virtual: server.virtual === true,
    streaming: server.streaming === true
  };
}

var serverTitleById = {};

function prettyServerTitle(id) {
  return serverTitleById[id] || id;
}

function organiseServers(servers) {
  var ordered = orderServersByLatency(servers.map(decorateServerEntry));
  serverTitleById = {};
  ordered.forEach(function (entry) {
    serverTitleById[entry.id] = entry.displayTitle;
  });
  return ordered;
}

function fetchServers() {
  return browser.runtime.sendMessage({ type: MESSAGE_GET_SERVERS }).then(function (response) {
    return {
      servers: response.servers,
      selectedServerId: response.selectedServerId
    };
  });
}

function loadPersistedServerId() {
  return browser.storage.local.get([STORAGE_KEY_USERNAME, 'piaServerId']).then(function (stored) {
    var value = stored.piaServerId || null;
    return (typeof value === 'string' && value) ? value : null;
  });
}

function persistSelectedServerId(serverId) {
  return browser.runtime.sendMessage({ type: MESSAGE_SET_SERVER, serverId: serverId });
}

function setSelectedServer(serverId) {
  selectedServerId = serverId;
  return persistSelectedServerId(serverId).then(function (response) {
    return response.selectedServerId;
  });
}

function selectServer(serverId) {
  return setSelectedServer(serverId).then(function (effectiveId) {
    refreshOrganisedList();
    refreshStatusPage();
    return effectiveId;
  }).catch(function (selectError) {
    showStatusMessage('Could not select that server.');
    if (typeof console !== 'undefined' && console.warn) {
      console.warn('[pia] server select failed:', selectError);
    }
  });
}

function createServerRow(server, isSelected) {
  var row = document.createElement('button');
  row.type = 'button';
  row.className = 'server-row' + (isSelected ? ' selected' : '');
  if (server.country) {
    var badge = document.createElement('span');
    badge.className = 'cc';
    badge.textContent = server.country;
    row.appendChild(badge);
  }
  var label = document.createElement('span');
  label.className = 'srv-title';
  label.textContent = server.displayTitle;
  row.appendChild(label);
  if (server.streaming) {
    row.appendChild(makeTagIcon('streaming', 'Streaming optimized'));
  }
  if (server.virtual) {
    row.appendChild(makeTagIcon('virtual', 'Virtual location'));
  }
  var latency = document.createElement('span');
  latency.className = 'latency';
  latency.textContent = formatLatency(server.latencyMs);
  row.appendChild(latency);
  row.addEventListener('click', function () {
    selectServer(server.id);
  });
  return row;
}

function renderOrganisedServers(servers, effectiveSelectedId) {
  clearOrganisedList();
  organiseServers(servers).forEach(function (server) {
    serverListElement.appendChild(createServerRow(server, server.id === effectiveSelectedId));
  });
}

// Prefer the stored default and pin it to the top so one tap switches.
function centraliseDefaultSelection(servers, storedId) {
  if (!servers.length) {
    return Promise.resolve(null);
  }
  var known = null;
  for (var i = 0; i < servers.length; i += 1) {
    if (servers[i].id === storedId) {
      known = servers[i].id;
      break;
    }
  }
  if (known) {
    return Promise.resolve(known);
  }
  var fastest = organiseServers(servers)[0];
  if (!fastest) {
    return Promise.resolve(null);
  }
  return persistSelectedServerId(fastest.id).then(function () {
    return fastest.id;
  }).catch(function () {
    return fastest.id;
  });
}

function refreshOrganisedList() {
  return fetchServers().then(function (result) {
    var servers = result.servers;
    var storedId = result.selectedServerId;
    if (!storedId) {
      storedId = loadPersistedServerIdSyncFallback();
    }
    return centraliseDefaultSelection(servers, storedId).then(function (effectiveId) {
      renderOrganisedServers(servers, effectiveId);
      // Titles are known only now: repaint the status page so the
      // server row shows the pretty name instead of the raw id.
      refreshStatusPage();
      if (effectiveId) {
        showStatusMessage('Selected server: ' + prettyServerTitle(effectiveId));
      } else {
        showStatusMessage('Choose a server below.');
      }
    });
  }).catch(function (loadError) {
    clearOrganisedList();
    showStatusMessage('Could not load the server list.');
    if (typeof console !== 'undefined' && console.warn) {
      console.warn('[pia] server list load failed:', loadError);
    }
  });
}

// Synchronous fallback for the persisted id already held in memory once
// the popup has been initialised; keeps the defaulting behaviour local.
function loadPersistedServerIdSyncFallback() {
  return selectedServerId;
}

/* ---- Status page ---- */

function fetchStatus() {
  return browser.runtime.sendMessage({ type: MESSAGE_GET_STATUS });
}

function renderStatus(status) {
  var state = 'Off';
  var cls = '';
  var dot = '';
  if (!status.hasCredentials) {
    state = 'Waiting for credentials';
  } else if (status.proxyActive) {
    state = 'Connected';
    cls = 'on';
    dot = 'on';
  } else if (status.blocked) {
    state = 'Blocked — kill switch';
    cls = 'blocked';
    dot = 'blocked';
  } else if (status.directLanOnly) {
    state = 'Direct (kill switch off)';
  }
  if (heroStateElement) {
    heroStateElement.textContent = state;
    heroStateElement.className = 'hero-state' + (cls ? ' ' + cls : '');
  }
  if (headerDotElement) {
    headerDotElement.className = 'dot' + (dot ? ' ' + dot : '');
  }
  if (statusServerElement) {
    statusServerElement.textContent = status.serverId ? prettyServerTitle(status.serverId) : '–';
  }
  if (statusProxyElement) {
    statusProxyElement.textContent = (status.proxyHost || '–') + ':' + (status.proxyPort || '–');
  }
  if (statusProxyAuthElement) {
    statusProxyAuthElement.textContent = status.hasProxyCredentials ? 'saved' : 'missing — add it in Account';
  }
  if (statusTokenElement) {
    statusTokenElement.textContent = (typeof status.tokenRemainingSec === 'number') ?
      formatTokenRemaining(status.tokenRemainingSec) : 'none';
  }
  renderKillswitch(status);
}

function refreshStatusPage() {
  if (statusPageMessageElement) {
    statusPageMessageElement.textContent = '';
  }
  return fetchStatus().then(renderStatus).catch(function (statusError) {
    if (statusPageMessageElement) {
      statusPageMessageElement.textContent = 'Could not read the proxy status.';
    }
    if (typeof console !== 'undefined' && console.warn) {
      console.warn('[pia] status load failed:', statusError);
    }
  });
}

/* ---- Kill-switch page ---- */

function renderKillswitch(status) {
  var enabled = status.killSwitchEnabled !== false;
  if (killswitchToggleElement) {
    killswitchToggleElement.checked = enabled;
  }
  if (killswitchStateElement) {
    killswitchStateElement.textContent = enabled ? 'On' : 'Off';
  }
  if (killswitchWarnElement) {
    killswitchWarnElement.style.display = enabled ? 'none' : 'block';
  }
  if (killswitchExplainElement) {
    if (enabled) {
      killswitchExplainElement.textContent = status.proxyActive ?
        'Proxy is up. If it drops, every connection outside the proxy is blocked until it recovers.' :
        'Proxy is down, so nothing leaves this browser right now. Local addresses stay in the bypass list while the proxy is up.';
    } else {
      killswitchExplainElement.textContent = 'Proxy failures fall back to a direct connection. Turn it on to block everything instead.';
    }
  }
}

function handleKillswitchToggle() {
  var enabled = killswitchToggleElement ? killswitchToggleElement.checked : true;
  return browser.runtime.sendMessage({ type: MESSAGE_SET_KILLSWITCH, enabled: enabled })
    .then(renderStatus)
    .catch(function (toggleError) {
      if (statusPageMessageElement) {
        statusPageMessageElement.textContent = 'Could not change the kill switch.';
      }
      if (typeof console !== 'undefined' && console.warn) {
        console.warn('[pia] kill switch toggle failed:', toggleError);
      }
    });
}

/* ---- Account page ---- */

function showCredentialsStatusMessage(text) {
  if (credentialsStatusElement) {
    credentialsStatusElement.textContent = text;
  }
}

// Prefill the usernames only; password fields always start empty.
function prefillStoredUsername() {
  return browser.storage.local.get([STORAGE_KEY_USERNAME, STORAGE_KEY_PROXY_USERNAME]).then(function (stored) {
    var value = stored[STORAGE_KEY_USERNAME] || '';
    if (usernameInputElement && typeof value === 'string') {
      usernameInputElement.value = value;
    }
    var proxyValue = stored[STORAGE_KEY_PROXY_USERNAME] || '';
    if (proxyUsernameInputElement && typeof proxyValue === 'string') {
      proxyUsernameInputElement.value = proxyValue;
    }
  }).catch(function (prefillError) {
    if (typeof console !== 'undefined' && console.warn) {
      console.warn('[pia] username prefill failed:', prefillError);
    }
  });
}

function saveCredentials(username, password, proxyUsername, proxyPassword) {
  var update = {};
  update[STORAGE_KEY_USERNAME] = username;
  update[STORAGE_KEY_PASSWORD] = password;
  update[STORAGE_KEY_PROXY_USERNAME] = proxyUsername;
  update[STORAGE_KEY_PROXY_PASSWORD] = proxyPassword;
  return browser.storage.local.set(update);
}

function handleCredentialsSave(event) {
  if (event) {
    event.preventDefault();
  }
  var username = usernameInputElement ? usernameInputElement.value : '';
  var password = passwordInputElement ? passwordInputElement.value : '';
  var proxyUsername = proxyUsernameInputElement ? proxyUsernameInputElement.value : '';
  var proxyPassword = proxyPasswordInputElement ? proxyPasswordInputElement.value : '';
  return saveCredentials(username, password, proxyUsername, proxyPassword).then(function () {
    showCredentialsStatusMessage('Credentials saved. Connecting…');
    return browser.runtime.sendMessage({ type: MESSAGE_RECONNECT }).then(function (status) {
      renderStatus(status);
      showCredentialsStatusMessage(status.proxyActive ? 'Connected.' : 'Saved. Proxy not up yet.');
    });
  }).catch(function (saveError) {
    showCredentialsStatusMessage('Could not save the credentials.');
    if (typeof console !== 'undefined' && console.warn) {
      console.warn('[pia] credentials save failed:', saveError);
    }
  });
}

function handleReconnect() {
  if (statusPageMessageElement) {
    statusPageMessageElement.textContent = 'Reconnecting…';
  }
  return browser.runtime.sendMessage({ type: MESSAGE_RECONNECT }).then(function (status) {
    renderStatus(status);
    if (statusPageMessageElement) {
      statusPageMessageElement.textContent = status.proxyActive ? '' : 'Proxy not up yet.';
    }
  }).catch(function (reconnectError) {
    if (statusPageMessageElement) {
      statusPageMessageElement.textContent = 'Reconnect failed.';
    }
    if (typeof console !== 'undefined' && console.warn) {
      console.warn('[pia] reconnect failed:', reconnectError);
    }
  });
}

/* ---- Tabs + init ---- */

function switchPage(pageId) {
  var pages = document.querySelectorAll('.page');
  for (var i = 0; i < pages.length; i += 1) {
    pages[i].classList.toggle('active', pages[i].id === pageId);
  }
  var tabs = document.querySelectorAll('nav button');
  for (var j = 0; j < tabs.length; j += 1) {
    tabs[j].classList.toggle('active', tabs[j].getAttribute('data-page') === pageId);
  }
  if (pageId === 'pageStatus') {
    refreshStatusPage();
  }
}

function initialisePopup() {
  serverListElement = document.getElementById('serverList');
  statusMessageElement = document.getElementById('statusMessage');
  refreshButtonElement = document.getElementById('refreshButton');
  credentialsFormElement = document.getElementById('credentialsForm');
  usernameInputElement = document.getElementById('piaUsernameInput');
  passwordInputElement = document.getElementById('piaPasswordInput');
  credentialsStatusElement = document.getElementById('credentialsStatusMessage');
  proxyUsernameInputElement = document.getElementById('piaProxyUsernameInput');
  proxyPasswordInputElement = document.getElementById('piaProxyPasswordInput');
  heroStateElement = document.getElementById('heroState');
  statusServerElement = document.getElementById('statusServer');
  statusProxyElement = document.getElementById('statusProxy');
  statusProxyAuthElement = document.getElementById('statusProxyAuth');
  statusTokenElement = document.getElementById('statusToken');
  statusPageMessageElement = document.getElementById('statusPageMessage');
  reconnectButtonElement = document.getElementById('reconnectButton');
  killswitchToggleElement = document.getElementById('killswitchToggle');
  killswitchStateElement = document.getElementById('killswitchState');
  killswitchExplainElement = document.getElementById('killswitchExplain');
  killswitchWarnElement = document.getElementById('killswitchWarn');
  headerDotElement = document.getElementById('headerDot');

  var tabs = document.querySelectorAll('nav button');
  for (var i = 0; i < tabs.length; i += 1) {
    (function (tab) {
      tab.addEventListener('click', function () {
        switchPage(tab.getAttribute('data-page'));
      });
    })(tabs[i]);
  }
  if (refreshButtonElement) {
    refreshButtonElement.addEventListener('click', function () {
      refreshOrganisedList();
    });
  }
  if (credentialsFormElement) {
    credentialsFormElement.addEventListener('submit', function (event) {
      handleCredentialsSave(event);
    });
  }
  if (reconnectButtonElement) {
    reconnectButtonElement.addEventListener('click', function () {
      handleReconnect();
    });
  }
  if (killswitchToggleElement) {
    killswitchToggleElement.addEventListener('change', function () {
      handleKillswitchToggle();
    });
  }
  prefillStoredUsername();
  refreshStatusPage();
  // Prefer the persisted choice already in storage so the lowest-latency
  // default only applies when nothing was stored before.
  loadPersistedServerId().then(function (storedId) {
    selectedServerId = storedId;
    return refreshOrganisedList();
  });
}

document.addEventListener('DOMContentLoaded', function () {
  initialisePopup();
});
