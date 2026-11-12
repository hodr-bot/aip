'use strict';

/* Account settings page (full tab): the popup can auto-close on focus loss,
 * this page cannot. Prefill never clobbers what the user already typed, and
 * every save is verified by reading the values back before connecting. */

var MESSAGE_RECONNECT = 'pia-reconnect';

var FIELD_KEYS = ['piaUsername', 'piaPassword', 'piaProxyUsername', 'piaProxyPassword'];
var INPUT_IDS = ['optUsername', 'optPassword', 'optProxyUsername', 'optProxyPassword'];

function $(id) {
  return document.getElementById(id);
}

function showMessage(text, isError) {
  var el = $('optionsMessage');
  if (!el) {
    return;
  }
  el.textContent = text;
  el.className = isError ? 'error' : '';
}

// Fill only fields the user has not touched: a late storage read must never
// wipe a username or password that is already being typed or pasted.
function prefillIfUntouched(input, value) {
  if (!input || typeof value !== 'string' || !value) {
    return;
  }
  if (document.activeElement === input) {
    return;
  }
  if (input.value) {
    return;
  }
  input.value = value;
}

function prefillStoredCredentials() {
  return browser.storage.local.get(FIELD_KEYS).then(function (stored) {
    for (var i = 0; i < FIELD_KEYS.length; i += 1) {
      prefillIfUntouched($(INPUT_IDS[i]), stored[FIELD_KEYS[i]]);
    }
  }).catch(function (error) {
    showMessage('Could not read saved credentials: ' + (error && error.message ? error.message : error), true);
  });
}

function readForm() {
  var values = {};
  for (var i = 0; i < FIELD_KEYS.length; i += 1) {
    var input = $(INPUT_IDS[i]);
    values[FIELD_KEYS[i]] = input ? input.value : '';
  }
  return values;
}

// Write, then read back and compare: a save the user cannot see is a save
// that did not happen. Only reconnect once the read-back matches.
function saveAndVerify(values) {
  return browser.storage.local.set(values).then(function () {
    return browser.storage.local.get(FIELD_KEYS);
  }).then(function (stored) {
    for (var i = 0; i < FIELD_KEYS.length; i += 1) {
      if ((stored[FIELD_KEYS[i]] || '') !== (values[FIELD_KEYS[i]] || '')) {
        throw new Error('read-back mismatch');
      }
    }
    return stored;
  });
}

function handleOptionsSave(event) {
  if (event) {
    event.preventDefault();
  }
  var saveButton = $('optSave');
  if (saveButton) {
    saveButton.disabled = true;
  }
  showMessage('Saving…', false);
  var values = readForm();
  saveAndVerify(values).then(function () {
    showMessage('Credentials saved. Connecting…', false);
    return browser.runtime.sendMessage({ type: MESSAGE_RECONNECT });
  }).then(function (status) {
    if (status && status.proxyActive) {
      showMessage('Saved. Connected.', false);
    } else if (status && status.lastFailure) {
      showMessage('Saved. ' + status.lastFailure, true);
    } else {
      showMessage('Saved. Proxy not up yet.', true);
    }
  }).catch(function (error) {
    showMessage(
      'Save failed: ' + (error && error.message ? error.message : error),
      true
    );
  }).then(function () {
    if (saveButton) {
      saveButton.disabled = false;
    }
  });
}

document.addEventListener('DOMContentLoaded', function () {
  var form = $('optionsCredentialsForm');
  if (form) {
    form.addEventListener('submit', handleOptionsSave);
  }
  prefillStoredCredentials();
});
