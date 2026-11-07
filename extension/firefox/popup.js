'use strict';

/* Firefox MV2 popup script (plain JS, no bundler).
 *
 * Lists servers from the live server-list endpoint through the background
 * script, stores the chosen server id in browser.storage.local, and
 * defaults to the lowest-latency pick when nothing is stored yet.
 * Mirrors ServerCatalog behaviour: lowest known latency wins, unknown
 * latency sorts last, ties break on id. Also hosts the minimal PIA
 * credentials form: username is prefilled on open, both keys are stored
 * in browser.storage.local on save. No secrets live here and secret
 * values are never logged.
 *
 * Identifiers favour en-GB spelling throughout.
 */

var STORAGE_KEY_SERVER_ID = 'piaServerId';
var STORAGE_KEY_USERNAME = 'piaUsername';
var STORAGE_KEY_PASSWORD = 'piaPassword';
var MESSAGE_GET_SERVERS = 'pia-get-servers';
var MESSAGE_SET_SERVER = 'pia-set-selected-server';

var serverListElement = null;
var statusMessageElement = null;
var refreshButtonElement = null;
var credentialsFormElement = null;
var usernameInputElement = null;
var passwordInputElement = null;
var credentialsStatusElement = null;
var organisedServerEntries = [];
var selectedServerId = null;

function compareOrganisedServers(a, b) {
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

// Favour the lowest-latency entry; unknown latency sorts last.
function favourLowestLatencyServer(servers) {
  if (!servers || !servers.length) {
    return null;
  }
  var organised = servers.slice().sort(compareOrganisedServers);
  return organised[0];
}

function formatLatencyLabel(latencyMs) {
  if (typeof latencyMs === 'number' && isFinite(latencyMs)) {
    return Math.round(latencyMs) + ' ms';
  }
  return 'latency unknown';
}

function showStatusMessage(text) {
  if (statusMessageElement) {
    statusMessageElement.textContent = text;
  }
}

function clearOrganisedList() {
  organisedServerEntries = [];
  if (serverListElement) {
    while (serverListElement.firstChild) {
      serverListElement.removeChild(serverListElement.firstChild);
    }
  }
}

function createServerButton(entry, isSelected) {
  var item = document.createElement('li');
  var button = document.createElement('button');
  button.type = 'button';
  button.setAttribute('aria-pressed', isSelected ? 'true' : 'false');
  button.dataset.serverId = entry.id;

  var label = document.createElement('span');
  label.textContent = entry.id + ' — ' + entry.hostname;

  var latency = document.createElement('span');
  latency.className = 'latency';
  latency.textContent = formatLatencyLabel(entry.latencyMs);

  button.appendChild(label);
  button.appendChild(latency);
  button.addEventListener('click', function () {
    handleServerChoice(entry.id);
  });
  item.appendChild(button);
  return item;
}

function renderOrganisedServers(servers, currentSelection) {
  clearOrganisedList();
  organisedServerEntries = servers.slice().sort(compareOrganisedServers);
  selectedServerId = currentSelection;
  organisedServerEntries.forEach(function (entry) {
    var isSelected = (entry.id === currentSelection);
    serverListElement.appendChild(createServerButton(entry, isSelected));
  });
}

function loadPersistedServerId() {
  return browser.storage.local.get([STORAGE_KEY_SERVER_ID]).then(function (stored) {
    var value = stored[STORAGE_KEY_SERVER_ID] || null;
    return (typeof value === 'string' && value) ? value : null;
  });
}

function persistSelectedServerId(serverId) {
  var update = {};
  update[STORAGE_KEY_SERVER_ID] = serverId;
  return browser.storage.local.set(update).then(function () {
    return serverId;
  });
}

function requestServersThroughBackground() {
  return browser.runtime.sendMessage({ type: MESSAGE_GET_SERVERS }).then(function (response) {
    var servers = (response && response.servers) || [];
    var storedId = (response && response.selectedServerId) || null;
    return { servers: servers, selectedServerId: storedId };
  });
}

function notifyBackgroundOfChoice(serverId) {
  return browser.runtime.sendMessage({ type: MESSAGE_SET_SERVER, serverId: serverId })
    .then(function () { return serverId; }, function () { return serverId; });
}

function handleServerChoice(serverId) {
  showStatusMessage('Selecting ' + serverId + '…');
  return persistSelectedServerId(serverId)
    .then(function (savedId) {
      return notifyBackgroundOfChoice(savedId);
    })
    .then(function (savedId) {
      renderOrganisedServers(organisedServerEntries, savedId);
      showStatusMessage('Selected server: ' + savedId);
    })
    .catch(function (choiceError) {
      showStatusMessage('Could not save the server choice.');
      if (typeof console !== 'undefined' && console.warn) {
        console.warn('[pia] server choice failed:', choiceError);
      }
    });
}

function centraliseDefaultSelection(servers, storedId) {
  if (storedId) {
    return Promise.resolve(storedId);
  }
  var favourite = favourLowestLatencyServer(servers);
  if (!favourite) {
    return Promise.resolve(null);
  }
  return persistSelectedServerId(favourite.id);
}

function refreshOrganisedList() {
  showStatusMessage('Loading servers…');
  return requestServersThroughBackground().then(function (result) {
    var servers = result.servers || [];
    if (!servers.length) {
      clearOrganisedList();
      showStatusMessage('The server list is empty.');
      return;
    }
    var storedId = result.selectedServerId;
    if (!storedId) {
      storedId = loadPersistedServerIdSyncFallback();
    }
    return centraliseDefaultSelection(servers, storedId).then(function (effectiveId) {
      renderOrganisedServers(servers, effectiveId);
      if (effectiveId) {
        showStatusMessage('Selected server: ' + effectiveId);
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

function showCredentialsStatusMessage(text) {
  if (credentialsStatusElement) {
    credentialsStatusElement.textContent = text;
  }
}

// Prefill the username only; the password field always starts empty.
function prefillStoredUsername() {
  return browser.storage.local.get([STORAGE_KEY_USERNAME]).then(function (stored) {
    var value = stored[STORAGE_KEY_USERNAME] || '';
    if (usernameInputElement && typeof value === 'string') {
      usernameInputElement.value = value;
    }
  }).catch(function (prefillError) {
    if (typeof console !== 'undefined' && console.warn) {
      console.warn('[pia] username prefill failed:', prefillError);
    }
  });
}

function saveCredentials(username, password) {
  var update = {};
  update[STORAGE_KEY_USERNAME] = username;
  update[STORAGE_KEY_PASSWORD] = password;
  return browser.storage.local.set(update);
}

function handleCredentialsSave(event) {
  if (event) {
    event.preventDefault();
  }
  var username = usernameInputElement ? usernameInputElement.value : '';
  var password = passwordInputElement ? passwordInputElement.value : '';
  return saveCredentials(username, password).then(function () {
    showCredentialsStatusMessage('Credentials saved.');
  }).catch(function (saveError) {
    showCredentialsStatusMessage('Could not save the credentials.');
    if (typeof console !== 'undefined' && console.warn) {
      console.warn('[pia] credentials save failed:', saveError);
    }
  });
}

function initialiseServerChoice() {
  serverListElement = document.getElementById('serverList');
  statusMessageElement = document.getElementById('statusMessage');
  refreshButtonElement = document.getElementById('refreshButton');
  credentialsFormElement = document.getElementById('credentialsForm');
  usernameInputElement = document.getElementById('piaUsernameInput');
  passwordInputElement = document.getElementById('piaPasswordInput');
  credentialsStatusElement = document.getElementById('credentialsStatusMessage');
  if (!serverListElement || !statusMessageElement) {
    return;
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
  prefillStoredUsername();
  // Prefer the persisted choice already in storage so the lowest-latency
  // default only applies when nothing was stored before.
  loadPersistedServerId().then(function (storedId) {
    selectedServerId = storedId;
    return refreshOrganisedList();
  });
}

document.addEventListener('DOMContentLoaded', function () {
  initialiseServerChoice();
});
