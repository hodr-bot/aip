(function (factory) {
  if (typeof define === 'function' && define.amd)
    define(['exports', './kotlin-kotlin-stdlib.js'], factory);
  else if (typeof exports === 'object')
    factory(module.exports, require('./kotlin-kotlin-stdlib.js'));
  else {
    if (typeof globalThis['kotlin-kotlin-stdlib'] === 'undefined') {
      throw new Error("Error loading module 'pia-core'. Its dependency 'kotlin-kotlin-stdlib' was not found. Please, check whether 'kotlin-kotlin-stdlib' is loaded prior to 'pia-core'.");
    }
    globalThis['pia-core'] = factory(typeof globalThis['pia-core'] === 'undefined' ? {} : globalThis['pia-core'], globalThis['kotlin-kotlin-stdlib']);
  }
}(function (_, kotlin_kotlin) {
  'use strict';
  //region block: imports
  var imul = Math.imul;
  var numberToLong = kotlin_kotlin.$_$.e1;
  var protoOf = kotlin_kotlin.$_$.f1;
  var initMetadataForObject = kotlin_kotlin.$_$.b1;
  var Long = kotlin_kotlin.$_$.u1;
  var defineProp = kotlin_kotlin.$_$.u;
  var THROW_IAE = kotlin_kotlin.$_$.w1;
  var enumEntries = kotlin_kotlin.$_$.o;
  var Unit_getInstance = kotlin_kotlin.$_$.f;
  var Enum = kotlin_kotlin.$_$.s1;
  var initMetadataForClass = kotlin_kotlin.$_$.y;
  var VOID = kotlin_kotlin.$_$.a;
  var Exception = kotlin_kotlin.$_$.t1;
  var Exception_init_$Init$ = kotlin_kotlin.$_$.d;
  var captureStack = kotlin_kotlin.$_$.q;
  var toString = kotlin_kotlin.$_$.g1;
  var hashCode = kotlin_kotlin.$_$.x;
  var THROW_CCE = kotlin_kotlin.$_$.v1;
  var equals = kotlin_kotlin.$_$.v;
  var initMetadataForCompanion = kotlin_kotlin.$_$.z;
  var indexOf = kotlin_kotlin.$_$.h1;
  var _Char___init__impl__6a9atx = kotlin_kotlin.$_$.e;
  var indexOf_0 = kotlin_kotlin.$_$.i1;
  var charSequenceGet = kotlin_kotlin.$_$.s;
  var toIntOrNull = kotlin_kotlin.$_$.n1;
  var isDigit = kotlin_kotlin.$_$.j1;
  var emptyList = kotlin_kotlin.$_$.h;
  var isCharSequence = kotlin_kotlin.$_$.c1;
  var trim = kotlin_kotlin.$_$.q1;
  var charSequenceLength = kotlin_kotlin.$_$.t;
  var charArrayOf = kotlin_kotlin.$_$.r;
  var split = kotlin_kotlin.$_$.l1;
  var ArrayList_init_$Create$ = kotlin_kotlin.$_$.c;
  var trim_0 = kotlin_kotlin.$_$.p1;
  var isWhitespace = kotlin_kotlin.$_$.k1;
  var to = kotlin_kotlin.$_$.y1;
  var mapOf = kotlin_kotlin.$_$.l;
  var emptyMap = kotlin_kotlin.$_$.i;
  var initMetadataForInterface = kotlin_kotlin.$_$.a1;
  var toString_0 = kotlin_kotlin.$_$.x1;
  var getStringHashCode = kotlin_kotlin.$_$.w;
  var FunctionAdapter = kotlin_kotlin.$_$.p;
  var isInterface = kotlin_kotlin.$_$.d1;
  var Comparator = kotlin_kotlin.$_$.r1;
  var compareValues = kotlin_kotlin.$_$.n;
  var minWithOrNull = kotlin_kotlin.$_$.m;
  var startsWith = kotlin_kotlin.$_$.m1;
  var collectionSizeOrDefault = kotlin_kotlin.$_$.g;
  var ArrayList_init_$Create$_0 = kotlin_kotlin.$_$.b;
  var listOf = kotlin_kotlin.$_$.k;
  var joinToString = kotlin_kotlin.$_$.j;
  var trimMargin = kotlin_kotlin.$_$.o1;
  //endregion
  //region block: pre-declaration
  initMetadataForObject(JsTimeBridge, 'JsTimeBridge');
  initMetadataForObject(JsConstants, 'JsConstants');
  initMetadataForClass(ConnectionState, 'ConnectionState', VOID, Enum);
  initMetadataForClass(TrafficVerdict, 'TrafficVerdict', VOID, Enum);
  initMetadataForObject(KillSwitch, 'KillSwitch');
  initMetadataForClass(PiaAuthError, 'PiaAuthError', VOID, Exception);
  initMetadataForClass(PiaApiError, 'PiaApiError', VOID, Exception);
  initMetadataForClass(LeaseResult, 'LeaseResult');
  initMetadataForCompanion(Companion);
  initMetadataForClass(PiaClient, 'PiaClient');
  function get$default(url, headers, $super) {
    headers = headers === VOID ? emptyMap() : headers;
    return $super === VOID ? this.get_ae53i0_k$(url, headers) : $super.get_ae53i0_k$.call(this, url, headers);
  }
  initMetadataForInterface(HttpPort, 'HttpPort');
  initMetadataForInterface(TokenStore, 'TokenStore');
  initMetadataForInterface(Clock, 'Clock');
  initMetadataForClass(RegionEndpoint, 'RegionEndpoint');
  initMetadataForClass(sam$kotlin_Comparator$0, 'sam$kotlin_Comparator$0', VOID, VOID, [Comparator, FunctionAdapter]);
  initMetadataForObject(RegionPicker, 'RegionPicker');
  initMetadataForClass(ServerEntry, 'ServerEntry');
  initMetadataForCompanion(Companion_0);
  initMetadataForClass(ServerCatalog, 'ServerCatalog');
  initMetadataForCompanion(Companion_1);
  initMetadataForClass(AuthToken, 'AuthToken');
  initMetadataForClass(WireGuardLease, 'WireGuardLease');
  initMetadataForObject(WireGuard, 'WireGuard');
  //endregion
  function JsTimeBridge() {
    JsTimeBridge_instance = this;
  }
  protoOf(JsTimeBridge).tokenRemainingSec = function (issuedAtEpochSec, ttlSec, nowEpochSec) {
    return (new AuthToken('js-bridge', numberToLong(issuedAtEpochSec), numberToLong(ttlSec))).remainingSec_rrxxar_k$(numberToLong(nowEpochSec)).toDouble_ygsx0s_k$();
  };
  protoOf(JsTimeBridge).tokenShouldRefresh = function (issuedAtEpochSec, nowEpochSec, refreshAfterSec) {
    return (new AuthToken('js-bridge', numberToLong(issuedAtEpochSec))).shouldRefresh_1u6opk_k$(numberToLong(nowEpochSec), numberToLong(refreshAfterSec));
  };
  var JsTimeBridge_instance;
  function JsTimeBridge_getInstance() {
    if (JsTimeBridge_instance == null)
      new JsTimeBridge();
    return JsTimeBridge_instance;
  }
  function JsConstants() {
    JsConstants_instance = this;
    this.TOKEN_TTL_SEC = (new Long(86400, 0)).toDouble_ygsx0s_k$();
    this.REFRESH_AFTER_SEC = (new Long(43200, 0)).toDouble_ygsx0s_k$();
  }
  protoOf(JsConstants).get_TOKEN_TTL_SEC_icezbl_k$ = function () {
    return this.TOKEN_TTL_SEC;
  };
  protoOf(JsConstants).get_REFRESH_AFTER_SEC_oe2iv5_k$ = function () {
    return this.REFRESH_AFTER_SEC;
  };
  var JsConstants_instance;
  function JsConstants_getInstance() {
    if (JsConstants_instance == null)
      new JsConstants();
    return JsConstants_instance;
  }
  var ConnectionState_CONNECTED_instance;
  var ConnectionState_CONNECTING_instance;
  var ConnectionState_DISCONNECTED_instance;
  var ConnectionState_DEGRADED_instance;
  function values() {
    return [ConnectionState_CONNECTED_getInstance(), ConnectionState_CONNECTING_getInstance(), ConnectionState_DISCONNECTED_getInstance(), ConnectionState_DEGRADED_getInstance()];
  }
  function valueOf(value) {
    switch (value) {
      case 'CONNECTED':
        return ConnectionState_CONNECTED_getInstance();
      case 'CONNECTING':
        return ConnectionState_CONNECTING_getInstance();
      case 'DISCONNECTED':
        return ConnectionState_DISCONNECTED_getInstance();
      case 'DEGRADED':
        return ConnectionState_DEGRADED_getInstance();
      default:
        ConnectionState_initEntries();
        THROW_IAE('No enum constant value.');
        break;
    }
  }
  function get_entries() {
    if ($ENTRIES == null)
      $ENTRIES = enumEntries(values());
    return $ENTRIES;
  }
  var ConnectionState_entriesInitialized;
  function ConnectionState_initEntries() {
    if (ConnectionState_entriesInitialized)
      return Unit_getInstance();
    ConnectionState_entriesInitialized = true;
    ConnectionState_CONNECTED_instance = new ConnectionState('CONNECTED', 0);
    ConnectionState_CONNECTING_instance = new ConnectionState('CONNECTING', 1);
    ConnectionState_DISCONNECTED_instance = new ConnectionState('DISCONNECTED', 2);
    ConnectionState_DEGRADED_instance = new ConnectionState('DEGRADED', 3);
  }
  var $ENTRIES;
  function ConnectionState(name, ordinal) {
    Enum.call(this, name, ordinal);
  }
  var TrafficVerdict_ALLOW_ALL_instance;
  var TrafficVerdict_ALLOW_LAN_ONLY_instance;
  var TrafficVerdict_BLOCK_ALL_instance;
  function values_0() {
    return [TrafficVerdict_ALLOW_ALL_getInstance(), TrafficVerdict_ALLOW_LAN_ONLY_getInstance(), TrafficVerdict_BLOCK_ALL_getInstance()];
  }
  function valueOf_0(value) {
    switch (value) {
      case 'ALLOW_ALL':
        return TrafficVerdict_ALLOW_ALL_getInstance();
      case 'ALLOW_LAN_ONLY':
        return TrafficVerdict_ALLOW_LAN_ONLY_getInstance();
      case 'BLOCK_ALL':
        return TrafficVerdict_BLOCK_ALL_getInstance();
      default:
        TrafficVerdict_initEntries();
        THROW_IAE('No enum constant value.');
        break;
    }
  }
  function get_entries_0() {
    if ($ENTRIES_0 == null)
      $ENTRIES_0 = enumEntries(values_0());
    return $ENTRIES_0;
  }
  var TrafficVerdict_entriesInitialized;
  function TrafficVerdict_initEntries() {
    if (TrafficVerdict_entriesInitialized)
      return Unit_getInstance();
    TrafficVerdict_entriesInitialized = true;
    TrafficVerdict_ALLOW_ALL_instance = new TrafficVerdict('ALLOW_ALL', 0);
    TrafficVerdict_ALLOW_LAN_ONLY_instance = new TrafficVerdict('ALLOW_LAN_ONLY', 1);
    TrafficVerdict_BLOCK_ALL_instance = new TrafficVerdict('BLOCK_ALL', 2);
  }
  var $ENTRIES_0;
  function TrafficVerdict(name, ordinal) {
    Enum.call(this, name, ordinal);
  }
  function KillSwitch() {
    KillSwitch_instance = this;
  }
  protoOf(KillSwitch).decide = function (state, killSwitchEnabled) {
    return state.equals(ConnectionState_CONNECTED_getInstance()) ? TrafficVerdict_ALLOW_ALL_getInstance() : killSwitchEnabled ? TrafficVerdict_BLOCK_ALL_getInstance() : TrafficVerdict_ALLOW_LAN_ONLY_getInstance();
  };
  var KillSwitch_instance;
  function KillSwitch_getInstance() {
    if (KillSwitch_instance == null)
      new KillSwitch();
    return KillSwitch_instance;
  }
  function ConnectionState_CONNECTED_getInstance() {
    ConnectionState_initEntries();
    return ConnectionState_CONNECTED_instance;
  }
  function ConnectionState_CONNECTING_getInstance() {
    ConnectionState_initEntries();
    return ConnectionState_CONNECTING_instance;
  }
  function ConnectionState_DISCONNECTED_getInstance() {
    ConnectionState_initEntries();
    return ConnectionState_DISCONNECTED_instance;
  }
  function ConnectionState_DEGRADED_getInstance() {
    ConnectionState_initEntries();
    return ConnectionState_DEGRADED_instance;
  }
  function TrafficVerdict_ALLOW_ALL_getInstance() {
    TrafficVerdict_initEntries();
    return TrafficVerdict_ALLOW_ALL_instance;
  }
  function TrafficVerdict_ALLOW_LAN_ONLY_getInstance() {
    TrafficVerdict_initEntries();
    return TrafficVerdict_ALLOW_LAN_ONLY_instance;
  }
  function TrafficVerdict_BLOCK_ALL_getInstance() {
    TrafficVerdict_initEntries();
    return TrafficVerdict_BLOCK_ALL_instance;
  }
  function PiaAuthError(message) {
    Exception_init_$Init$(message, this);
    captureStack(this, PiaAuthError);
  }
  function PiaApiError(message) {
    Exception_init_$Init$(message, this);
    captureStack(this, PiaApiError);
  }
  function LeaseResult(lease, dnsServers) {
    this.lease_1 = lease;
    this.dnsServers_1 = dnsServers;
  }
  protoOf(LeaseResult).get_lease_iuls8h_k$ = function () {
    return this.lease_1;
  };
  protoOf(LeaseResult).get_dnsServers_pt73xs_k$ = function () {
    return this.dnsServers_1;
  };
  protoOf(LeaseResult).component1_7eebsc_k$ = function () {
    return this.lease_1;
  };
  protoOf(LeaseResult).component2_7eebsb_k$ = function () {
    return this.dnsServers_1;
  };
  protoOf(LeaseResult).copy_l2pz3s_k$ = function (lease, dnsServers) {
    return new LeaseResult(lease, dnsServers);
  };
  protoOf(LeaseResult).copy$default_u7h4ke_k$ = function (lease, dnsServers, $super) {
    lease = lease === VOID ? this.lease_1 : lease;
    dnsServers = dnsServers === VOID ? this.dnsServers_1 : dnsServers;
    return $super === VOID ? this.copy_l2pz3s_k$(lease, dnsServers) : $super.copy_l2pz3s_k$.call(this, lease, dnsServers);
  };
  protoOf(LeaseResult).toString = function () {
    return 'LeaseResult(lease=' + this.lease_1.toString() + ', dnsServers=' + toString(this.dnsServers_1) + ')';
  };
  protoOf(LeaseResult).hashCode = function () {
    var result = this.lease_1.hashCode();
    result = imul(result, 31) + hashCode(this.dnsServers_1) | 0;
    return result;
  };
  protoOf(LeaseResult).equals = function (other) {
    if (this === other)
      return true;
    if (!(other instanceof LeaseResult))
      return false;
    var tmp0_other_with_cast = other instanceof LeaseResult ? other : THROW_CCE();
    if (!this.lease_1.equals(tmp0_other_with_cast.lease_1))
      return false;
    if (!equals(this.dnsServers_1, tmp0_other_with_cast.dnsServers_1))
      return false;
    return true;
  };
  function _get_http__d7tner($this) {
    return $this.http_1;
  }
  function _get_clock__ixv2bh($this) {
    return $this.clock_1;
  }
  function Companion() {
    Companion_instance = this;
    this.TOKEN_URL_1 = 'https://www.privateinternetaccess.com/api/client/v2/token';
    this.ADD_KEY_PORT_1 = 1337;
    this.ADD_KEY_PATH_1 = 'addKey';
  }
  protoOf(Companion).get_TOKEN_URL_e2iny8_k$ = function () {
    return this.TOKEN_URL_1;
  };
  protoOf(Companion).get_ADD_KEY_PORT_ffc1fc_k$ = function () {
    return this.ADD_KEY_PORT_1;
  };
  protoOf(Companion).get_ADD_KEY_PATH_ffcbro_k$ = function () {
    return this.ADD_KEY_PATH_1;
  };
  var Companion_instance;
  function Companion_getInstance() {
    if (Companion_instance == null)
      new Companion();
    return Companion_instance;
  }
  function findStringField($this, body, field) {
    var key = '"' + field + '"';
    var index = indexOf(body, key);
    while (index >= 0) {
      var cursor = index + key.length | 0;
      cursor = skipSeparators($this, body, cursor);
      if (cursor < body.length && charSequenceGet(body, cursor) === _Char___init__impl__6a9atx(58)) {
        cursor = skipSeparators($this, body, cursor + 1 | 0);
        if (cursor < body.length && charSequenceGet(body, cursor) === _Char___init__impl__6a9atx(34)) {
          var start = cursor + 1 | 0;
          var end = indexOf_0(body, _Char___init__impl__6a9atx(34), start);
          if (end > start) {
            // Inline function 'kotlin.text.substring' call
            // Inline function 'kotlin.js.asDynamic' call
            return body.substring(start, end);
          }
          return null;
        }
        return null;
      }
      index = indexOf(body, key, index + 1 | 0);
    }
    return null;
  }
  function findIntField($this, body, field) {
    var quoted = findStringField($this, body, field);
    if (!(quoted == null)) {
      return toIntOrNull(quoted);
    }
    var key = '"' + field + '"';
    var index = indexOf(body, key);
    while (index >= 0) {
      var cursor = index + key.length | 0;
      cursor = skipSeparators($this, body, cursor);
      if (cursor < body.length && charSequenceGet(body, cursor) === _Char___init__impl__6a9atx(58)) {
        cursor = skipSeparators($this, body, cursor + 1 | 0);
        var start = cursor;
        while (cursor < body.length && (isDigit(charSequenceGet(body, cursor)) || charSequenceGet(body, cursor) === _Char___init__impl__6a9atx(45))) {
          cursor = cursor + 1 | 0;
        }
        if (start < cursor) {
          // Inline function 'kotlin.text.substring' call
          var endIndex = cursor;
          // Inline function 'kotlin.js.asDynamic' call
          var tmp$ret$1 = body.substring(start, endIndex);
          return toIntOrNull(tmp$ret$1);
        }
        return null;
      }
      index = indexOf(body, key, index + 1 | 0);
    }
    return null;
  }
  function findStringArray($this, body, field) {
    var key = '"' + field + '"';
    var index = indexOf(body, key);
    while (index >= 0) {
      var cursor = index + key.length | 0;
      cursor = skipSeparators($this, body, cursor);
      if (cursor < body.length && charSequenceGet(body, cursor) === _Char___init__impl__6a9atx(58)) {
        cursor = skipSeparators($this, body, cursor + 1 | 0);
        if (cursor < body.length && charSequenceGet(body, cursor) === _Char___init__impl__6a9atx(91)) {
          var end = indexOf_0(body, _Char___init__impl__6a9atx(93), cursor + 1 | 0);
          if (end < 0) {
            return emptyList();
          }
          // Inline function 'kotlin.text.trim' call
          // Inline function 'kotlin.text.substring' call
          var startIndex = cursor + 1 | 0;
          // Inline function 'kotlin.js.asDynamic' call
          var this_0 = body.substring(startIndex, end);
          var inner = toString(trim(isCharSequence(this_0) ? this_0 : THROW_CCE()));
          // Inline function 'kotlin.text.isEmpty' call
          if (charSequenceLength(inner) === 0) {
            return emptyList();
          }
          // Inline function 'kotlin.collections.mapNotNull' call
          // Inline function 'kotlin.collections.mapNotNullTo' call
          var this_1 = split(inner, charArrayOf([_Char___init__impl__6a9atx(44)]));
          var destination = ArrayList_init_$Create$();
          // Inline function 'kotlin.collections.forEach' call
          var tmp0_iterator = this_1.iterator_jk1svi_k$();
          while (tmp0_iterator.hasNext_bitz1p_k$()) {
            var element = tmp0_iterator.next_20eer_k$();
            // Inline function 'kotlin.collections.mapNotNullTo.<anonymous>' call
            // Inline function 'pia.core.PiaClient.findStringArray.<anonymous>' call
            // Inline function 'kotlin.text.trim' call
            // Inline function 'kotlin.text.trim' call
            var tmp$ret$4 = toString(trim(isCharSequence(element) ? element : THROW_CCE()));
            var this_2 = trim_0(tmp$ret$4, charArrayOf([_Char___init__impl__6a9atx(34)]));
            var cleaned = toString(trim(isCharSequence(this_2) ? this_2 : THROW_CCE()));
            var tmp;
            // Inline function 'kotlin.text.isEmpty' call
            if (charSequenceLength(cleaned) === 0) {
              tmp = null;
            } else {
              tmp = cleaned;
            }
            var tmp0_safe_receiver = tmp;
            if (tmp0_safe_receiver == null)
              null;
            else {
              // Inline function 'kotlin.let' call
              // Inline function 'kotlin.contracts.contract' call
              destination.add_utx5q5_k$(tmp0_safe_receiver);
            }
          }
          return destination;
        }
        return emptyList();
      }
      index = indexOf(body, key, index + 1 | 0);
    }
    return emptyList();
  }
  function skipSeparators($this, body, from) {
    var cursor = from;
    while (cursor < body.length && isWhitespace(charSequenceGet(body, cursor))) {
      cursor = cursor + 1 | 0;
    }
    return cursor;
  }
  function PiaClient(http, clock) {
    Companion_getInstance();
    this.http_1 = http;
    this.clock_1 = clock;
  }
  protoOf(PiaClient).login_l7yc9j_k$ = function (username, password) {
    var body = this.http_1.postForm_4yekay_k$('https://www.privateinternetaccess.com/api/client/v2/token', mapOf([to('username', username), to('password', password)]));
    var status = findStringField(this, body, 'status');
    var token = findStringField(this, body, 'token');
    if (!(status == null) && !(status === 'OK')) {
      throw new PiaAuthError('login rejected with status: ' + status);
    }
    // Inline function 'kotlin.text.isNullOrEmpty' call
    // Inline function 'kotlin.contracts.contract' call
    if (token == null || charSequenceLength(token) === 0) {
      throw new PiaAuthError('login response held no token');
    }
    return new AuthToken(token, this.clock_1.nowEpochSec_jl998i_k$());
  };
  protoOf(PiaClient).ensureValidToken_701zwq_k$ = function (store) {
    var now = this.clock_1.nowEpochSec_jl998i_k$();
    var cached = store.loadToken_mt6rar_k$();
    if (!(cached == null) && !cached.shouldRefresh$default_uxnj2n_k$(now)) {
      return cached;
    }
    var username = store.loadUsername_gu4n30_k$();
    var password = store.loadPassword_to80v3_k$();
    var tmp;
    // Inline function 'kotlin.text.isNullOrEmpty' call
    // Inline function 'kotlin.contracts.contract' call
    if (username == null || charSequenceLength(username) === 0) {
      tmp = true;
    } else {
      // Inline function 'kotlin.text.isNullOrEmpty' call
      // Inline function 'kotlin.contracts.contract' call
      tmp = password == null || charSequenceLength(password) === 0;
    }
    if (tmp) {
      if (cached == null) {
        throw new PiaAuthError('no stored token and no stored credentials');
      }
      throw new PiaAuthError('stored token needs refresh but credentials are missing');
    }
    var refreshed = this.login_l7yc9j_k$(username, password);
    store.saveToken_s9qpm0_k$(refreshed);
    return refreshed;
  };
  protoOf(PiaClient).addKey_iqi2wy_k$ = function (hostname, authToken, publicKey) {
    return this.addKey_fn9fng_k$(hostname, authToken.get_value_j01efc_k$(), publicKey);
  };
  protoOf(PiaClient).addKey_fn9fng_k$ = function (hostname, token, publicKey) {
    var url = 'https://' + hostname + ':1337/addKey?pt=' + token + '&pubkey=' + publicKey;
    var body = this.http_1.get$default_3gqdgv_k$(url);
    var status = findStringField(this, body, 'status');
    if (!(status === 'OK')) {
      throw new PiaApiError('addKey rejected with status: ' + status);
    }
    var peerIp = findStringField(this, body, 'peer_ip');
    var serverKey = findStringField(this, body, 'server_key');
    var serverPort = findIntField(this, body, 'server_port');
    var tmp;
    var tmp_0;
    // Inline function 'kotlin.text.isNullOrEmpty' call
    // Inline function 'kotlin.contracts.contract' call
    if (peerIp == null || charSequenceLength(peerIp) === 0) {
      tmp_0 = true;
    } else {
      // Inline function 'kotlin.text.isNullOrEmpty' call
      // Inline function 'kotlin.contracts.contract' call
      tmp_0 = serverKey == null || charSequenceLength(serverKey) === 0;
    }
    if (tmp_0) {
      tmp = true;
    } else {
      tmp = serverPort == null;
    }
    if (tmp) {
      throw new PiaApiError('addKey response missed peer_ip, server_key or server_port');
    }
    var dnsServers = findStringArray(this, body, 'dns_servers');
    var lease = new WireGuardLease(peerIp, serverKey, hostname + ':' + serverPort);
    return new LeaseResult(lease, dnsServers);
  };
  function HttpPort() {
  }
  function TokenStore() {
  }
  function Clock() {
  }
  function RegionEndpoint(id, host, port, latencyMs) {
    port = port === VOID ? 1337 : port;
    latencyMs = latencyMs === VOID ? null : latencyMs;
    this.id_1 = id;
    this.host_1 = host;
    this.port_1 = port;
    this.latencyMs_1 = latencyMs;
  }
  protoOf(RegionEndpoint).get_id_kntnx8_k$ = function () {
    return this.id_1;
  };
  protoOf(RegionEndpoint).get_host_wonf8x_k$ = function () {
    return this.host_1;
  };
  protoOf(RegionEndpoint).get_port_wosj4a_k$ = function () {
    return this.port_1;
  };
  protoOf(RegionEndpoint).get_latencyMs_lf8qcl_k$ = function () {
    return this.latencyMs_1;
  };
  protoOf(RegionEndpoint).component1_7eebsc_k$ = function () {
    return this.id_1;
  };
  protoOf(RegionEndpoint).component2_7eebsb_k$ = function () {
    return this.host_1;
  };
  protoOf(RegionEndpoint).component3_7eebsa_k$ = function () {
    return this.port_1;
  };
  protoOf(RegionEndpoint).component4_7eebs9_k$ = function () {
    return this.latencyMs_1;
  };
  protoOf(RegionEndpoint).copy_esosso_k$ = function (id, host, port, latencyMs) {
    return new RegionEndpoint(id, host, port, latencyMs);
  };
  protoOf(RegionEndpoint).copy$default_2jgt0y_k$ = function (id, host, port, latencyMs, $super) {
    id = id === VOID ? this.id_1 : id;
    host = host === VOID ? this.host_1 : host;
    port = port === VOID ? this.port_1 : port;
    latencyMs = latencyMs === VOID ? this.latencyMs_1 : latencyMs;
    return $super === VOID ? this.copy_esosso_k$(id, host, port, latencyMs) : $super.copy_esosso_k$.call(this, id, host, port, latencyMs);
  };
  protoOf(RegionEndpoint).toString = function () {
    return 'RegionEndpoint(id=' + this.id_1 + ', host=' + this.host_1 + ', port=' + this.port_1 + ', latencyMs=' + toString_0(this.latencyMs_1) + ')';
  };
  protoOf(RegionEndpoint).hashCode = function () {
    var result = getStringHashCode(this.id_1);
    result = imul(result, 31) + getStringHashCode(this.host_1) | 0;
    result = imul(result, 31) + this.port_1 | 0;
    result = imul(result, 31) + (this.latencyMs_1 == null ? 0 : this.latencyMs_1.hashCode()) | 0;
    return result;
  };
  protoOf(RegionEndpoint).equals = function (other) {
    if (this === other)
      return true;
    if (!(other instanceof RegionEndpoint))
      return false;
    var tmp0_other_with_cast = other instanceof RegionEndpoint ? other : THROW_CCE();
    if (!(this.id_1 === tmp0_other_with_cast.id_1))
      return false;
    if (!(this.host_1 === tmp0_other_with_cast.host_1))
      return false;
    if (!(this.port_1 === tmp0_other_with_cast.port_1))
      return false;
    if (!equals(this.latencyMs_1, tmp0_other_with_cast.latencyMs_1))
      return false;
    return true;
  };
  function sam$kotlin_Comparator$0(function_0) {
    this.function_1 = function_0;
  }
  protoOf(sam$kotlin_Comparator$0).compare_bczr_k$ = function (a, b) {
    return this.function_1(a, b);
  };
  protoOf(sam$kotlin_Comparator$0).compare = function (a, b) {
    return this.compare_bczr_k$(a, b);
  };
  protoOf(sam$kotlin_Comparator$0).getFunctionDelegate_jtodtf_k$ = function () {
    return this.function_1;
  };
  protoOf(sam$kotlin_Comparator$0).equals = function (other) {
    var tmp;
    if (!(other == null) ? isInterface(other, Comparator) : false) {
      var tmp_0;
      if (!(other == null) ? isInterface(other, FunctionAdapter) : false) {
        tmp_0 = equals(this.getFunctionDelegate_jtodtf_k$(), other.getFunctionDelegate_jtodtf_k$());
      } else {
        tmp_0 = false;
      }
      tmp = tmp_0;
    } else {
      tmp = false;
    }
    return tmp;
  };
  protoOf(sam$kotlin_Comparator$0).hashCode = function () {
    return hashCode(this.getFunctionDelegate_jtodtf_k$());
  };
  function RegionPicker$pickBest$lambda(a, b) {
    // Inline function 'kotlin.comparisons.compareValuesBy' call
    // Inline function 'pia.core.RegionPicker.pickBest.<anonymous>' call
    var tmp0_elvis_lhs = a.latencyMs_1;
    var tmp = tmp0_elvis_lhs == null ? new Long(-1, 2147483647) : tmp0_elvis_lhs;
    // Inline function 'pia.core.RegionPicker.pickBest.<anonymous>' call
    var tmp0_elvis_lhs_0 = b.latencyMs_1;
    var tmp$ret$1 = tmp0_elvis_lhs_0 == null ? new Long(-1, 2147483647) : tmp0_elvis_lhs_0;
    return compareValues(tmp, tmp$ret$1);
  }
  function RegionPicker$pickBest$lambda_0($this) {
    return function (a, b) {
      var previousCompare = $this.compare(a, b);
      var tmp;
      if (!(previousCompare === 0)) {
        tmp = previousCompare;
      } else {
        // Inline function 'kotlin.comparisons.compareValuesBy' call
        // Inline function 'pia.core.RegionPicker.pickBest.<anonymous>' call
        var tmp_0 = a.id_1;
        // Inline function 'pia.core.RegionPicker.pickBest.<anonymous>' call
        var tmp$ret$1 = b.id_1;
        tmp = compareValues(tmp_0, tmp$ret$1);
      }
      return tmp;
    };
  }
  function RegionPicker() {
    RegionPicker_instance = this;
  }
  protoOf(RegionPicker).pickBest_8f64uv_k$ = function (candidates) {
    // Inline function 'kotlin.comparisons.thenBy' call
    // Inline function 'kotlin.comparisons.compareBy' call
    var tmp = RegionPicker$pickBest$lambda;
    var this_0 = new sam$kotlin_Comparator$0(tmp);
    var tmp_0 = RegionPicker$pickBest$lambda_0(this_0);
    var tmp$ret$1 = new sam$kotlin_Comparator$0(tmp_0);
    return minWithOrNull(candidates, tmp$ret$1);
  };
  var RegionPicker_instance;
  function RegionPicker_getInstance() {
    if (RegionPicker_instance == null)
      new RegionPicker();
    return RegionPicker_instance;
  }
  function ServerEntry(id, hostname, latencyMs) {
    latencyMs = latencyMs === VOID ? null : latencyMs;
    this.id_1 = id;
    this.hostname_1 = hostname;
    this.latencyMs_1 = latencyMs;
  }
  protoOf(ServerEntry).get_id_kntnx8_k$ = function () {
    return this.id_1;
  };
  protoOf(ServerEntry).get_hostname_y9ohdo_k$ = function () {
    return this.hostname_1;
  };
  protoOf(ServerEntry).get_latencyMs_lf8qcl_k$ = function () {
    return this.latencyMs_1;
  };
  protoOf(ServerEntry).component1_7eebsc_k$ = function () {
    return this.id_1;
  };
  protoOf(ServerEntry).component2_7eebsb_k$ = function () {
    return this.hostname_1;
  };
  protoOf(ServerEntry).component3_7eebsa_k$ = function () {
    return this.latencyMs_1;
  };
  protoOf(ServerEntry).copy_3s0fwa_k$ = function (id, hostname, latencyMs) {
    return new ServerEntry(id, hostname, latencyMs);
  };
  protoOf(ServerEntry).copy$default_ozv33p_k$ = function (id, hostname, latencyMs, $super) {
    id = id === VOID ? this.id_1 : id;
    hostname = hostname === VOID ? this.hostname_1 : hostname;
    latencyMs = latencyMs === VOID ? this.latencyMs_1 : latencyMs;
    return $super === VOID ? this.copy_3s0fwa_k$(id, hostname, latencyMs) : $super.copy_3s0fwa_k$.call(this, id, hostname, latencyMs);
  };
  protoOf(ServerEntry).toString = function () {
    return 'ServerEntry(id=' + this.id_1 + ', hostname=' + this.hostname_1 + ', latencyMs=' + toString_0(this.latencyMs_1) + ')';
  };
  protoOf(ServerEntry).hashCode = function () {
    var result = getStringHashCode(this.id_1);
    result = imul(result, 31) + getStringHashCode(this.hostname_1) | 0;
    result = imul(result, 31) + (this.latencyMs_1 == null ? 0 : this.latencyMs_1.hashCode()) | 0;
    return result;
  };
  protoOf(ServerEntry).equals = function (other) {
    if (this === other)
      return true;
    if (!(other instanceof ServerEntry))
      return false;
    var tmp0_other_with_cast = other instanceof ServerEntry ? other : THROW_CCE();
    if (!(this.id_1 === tmp0_other_with_cast.id_1))
      return false;
    if (!(this.hostname_1 === tmp0_other_with_cast.hostname_1))
      return false;
    if (!equals(this.latencyMs_1, tmp0_other_with_cast.latencyMs_1))
      return false;
    return true;
  };
  function _get_http__d7tner_0($this) {
    return $this.http_1;
  }
  function Companion_0() {
    Companion_instance_0 = this;
    this.SERVER_LIST_URL_1 = 'https://serverlist.piaservers.net/vpninfo/servers/v6';
  }
  protoOf(Companion_0).get_SERVER_LIST_URL_vzd0w1_k$ = function () {
    return this.SERVER_LIST_URL_1;
  };
  var Companion_instance_0;
  function Companion_getInstance_0() {
    if (Companion_instance_0 == null)
      new Companion_0();
    return Companion_instance_0;
  }
  function parseRegions($this, body) {
    // Inline function 'kotlin.collections.mutableListOf' call
    var entries = ArrayList_init_$Create$();
    var regionsIndex = indexOf(body, '"regions"');
    if (regionsIndex < 0) {
      return entries;
    }
    var cursor = skipWs($this, body, regionsIndex + 9 | 0);
    if (cursor >= body.length || !(charSequenceGet(body, cursor) === _Char___init__impl__6a9atx(58))) {
      return entries;
    }
    cursor = skipWs($this, body, cursor + 1 | 0);
    if (cursor >= body.length || !(charSequenceGet(body, cursor) === _Char___init__impl__6a9atx(91))) {
      return entries;
    }
    cursor = cursor + 1 | 0;
    $l$loop_2: while (true) {
      cursor = skipWs($this, body, cursor);
      if (cursor >= body.length || charSequenceGet(body, cursor) === _Char___init__impl__6a9atx(93)) {
        break $l$loop_2;
      }
      if (charSequenceGet(body, cursor) === _Char___init__impl__6a9atx(44)) {
        cursor = cursor + 1 | 0;
        continue $l$loop_2;
      }
      if (!(charSequenceGet(body, cursor) === _Char___init__impl__6a9atx(123))) {
        break $l$loop_2;
      }
      var tmp0_elvis_lhs = extractObject($this, body, cursor);
      var tmp;
      if (tmp0_elvis_lhs == null) {
        break $l$loop_2;
      } else {
        tmp = tmp0_elvis_lhs;
      }
      var region = tmp;
      cursor = region.get_second_jf7fjx_k$();
      var tmp1_elvis_lhs = findBoolField($this, region.get_first_irdx8n_k$(), 'offline');
      var offline = tmp1_elvis_lhs == null ? false : tmp1_elvis_lhs;
      if (!offline) {
        var id = findStringField_0($this, region.get_first_irdx8n_k$(), 'id');
        var dns = findStringField_0($this, region.get_first_irdx8n_k$(), 'dns');
        var tmp_0;
        // Inline function 'kotlin.text.isNullOrEmpty' call
        // Inline function 'kotlin.contracts.contract' call
        if (!(id == null || charSequenceLength(id) === 0)) {
          // Inline function 'kotlin.text.isNullOrEmpty' call
          // Inline function 'kotlin.contracts.contract' call
          tmp_0 = !(dns == null || charSequenceLength(dns) === 0);
        } else {
          tmp_0 = false;
        }
        if (tmp_0) {
          entries.add_utx5q5_k$(new ServerEntry(id, dns));
        }
      }
    }
    return entries;
  }
  function extractObject($this, body, start) {
    var index = start;
    var depth = 0;
    var inString = false;
    var escaped = false;
    while (index < body.length) {
      var char = charSequenceGet(body, index);
      if (inString) {
        if (escaped)
          escaped = false;
        else if (char === _Char___init__impl__6a9atx(92))
          escaped = true;
        else if (char === _Char___init__impl__6a9atx(34))
          inString = false;
      } else {
        if (char === _Char___init__impl__6a9atx(34))
          inString = true;
        else if (char === _Char___init__impl__6a9atx(123))
          depth = depth + 1 | 0;
        else if (char === _Char___init__impl__6a9atx(125)) {
          depth = depth - 1 | 0;
          if (depth === 0) {
            // Inline function 'kotlin.text.substring' call
            var endIndex = index + 1 | 0;
            // Inline function 'kotlin.js.asDynamic' call
            var tmp$ret$1 = body.substring(start, endIndex);
            return to(tmp$ret$1, index + 1 | 0);
          }
        }
      }
      index = index + 1 | 0;
    }
    return null;
  }
  function findStringField_0($this, body, field) {
    var key = '"' + field + '"';
    var index = indexOf(body, key);
    while (index >= 0) {
      var cursor = skipWs($this, body, index + key.length | 0);
      if (cursor < body.length && charSequenceGet(body, cursor) === _Char___init__impl__6a9atx(58)) {
        cursor = skipWs($this, body, cursor + 1 | 0);
        if (cursor < body.length && charSequenceGet(body, cursor) === _Char___init__impl__6a9atx(34)) {
          var start = cursor + 1 | 0;
          var end = indexOf_0(body, _Char___init__impl__6a9atx(34), start);
          if (end > start) {
            // Inline function 'kotlin.text.substring' call
            // Inline function 'kotlin.js.asDynamic' call
            return body.substring(start, end);
          }
          return null;
        }
        return null;
      }
      index = indexOf(body, key, index + 1 | 0);
    }
    return null;
  }
  function findBoolField($this, body, field) {
    var key = '"' + field + '"';
    var index = indexOf(body, key);
    while (index >= 0) {
      var cursor = skipWs($this, body, index + key.length | 0);
      if (cursor < body.length && charSequenceGet(body, cursor) === _Char___init__impl__6a9atx(58)) {
        cursor = skipWs($this, body, cursor + 1 | 0);
        // Inline function 'kotlin.text.substring' call
        var startIndex = cursor;
        // Inline function 'kotlin.js.asDynamic' call
        var rest = body.substring(startIndex);
        return startsWith(rest, 'true') ? true : startsWith(rest, 'false') ? false : null;
      }
      index = indexOf(body, key, index + 1 | 0);
    }
    return null;
  }
  function skipWs($this, body, from) {
    var cursor = from;
    while (cursor < body.length && isWhitespace(charSequenceGet(body, cursor))) {
      cursor = cursor + 1 | 0;
    }
    return cursor;
  }
  function ServerCatalog(http) {
    Companion_getInstance_0();
    this.http_1 = http;
  }
  protoOf(ServerCatalog).fetchServers_7uiyru_k$ = function () {
    var entries = parseRegions(this, this.http_1.get$default_3gqdgv_k$('https://serverlist.piaservers.net/vpninfo/servers/v6'));
    if (entries.isEmpty_y1axqb_k$()) {
      throw new PiaApiError('server list held no regions');
    }
    return entries;
  };
  protoOf(ServerCatalog).pickById_eyyjgd_k$ = function (servers, id) {
    var tmp$ret$1;
    $l$block: {
      // Inline function 'kotlin.collections.firstOrNull' call
      var tmp0_iterator = servers.iterator_jk1svi_k$();
      while (tmp0_iterator.hasNext_bitz1p_k$()) {
        var element = tmp0_iterator.next_20eer_k$();
        // Inline function 'pia.core.ServerCatalog.pickById.<anonymous>' call
        if (element.id_1 === id) {
          tmp$ret$1 = element;
          break $l$block;
        }
      }
      tmp$ret$1 = null;
    }
    return tmp$ret$1;
  };
  protoOf(ServerCatalog).pickBest_4vc6b9_k$ = function (servers) {
    var tmp = RegionPicker_getInstance();
    // Inline function 'kotlin.collections.map' call
    // Inline function 'kotlin.collections.mapTo' call
    var destination = ArrayList_init_$Create$_0(collectionSizeOrDefault(servers, 10));
    var tmp0_iterator = servers.iterator_jk1svi_k$();
    while (tmp0_iterator.hasNext_bitz1p_k$()) {
      var item = tmp0_iterator.next_20eer_k$();
      // Inline function 'pia.core.ServerCatalog.pickBest.<anonymous>' call
      var tmp$ret$0 = new RegionEndpoint(item.id_1, item.hostname_1, VOID, item.latencyMs_1);
      destination.add_utx5q5_k$(tmp$ret$0);
    }
    var tmp0_elvis_lhs = tmp.pickBest_8f64uv_k$(destination);
    var tmp_0;
    if (tmp0_elvis_lhs == null) {
      return null;
    } else {
      tmp_0 = tmp0_elvis_lhs;
    }
    var best = tmp_0;
    var tmp$ret$4;
    $l$block: {
      // Inline function 'kotlin.collections.firstOrNull' call
      var tmp0_iterator_0 = servers.iterator_jk1svi_k$();
      while (tmp0_iterator_0.hasNext_bitz1p_k$()) {
        var element = tmp0_iterator_0.next_20eer_k$();
        // Inline function 'pia.core.ServerCatalog.pickBest.<anonymous>' call
        if (element.id_1 === best.get_id_kntnx8_k$()) {
          tmp$ret$4 = element;
          break $l$block;
        }
      }
      tmp$ret$4 = null;
    }
    return tmp$ret$4;
  };
  function Companion_1() {
    Companion_instance_1 = this;
    this.TOKEN_TTL_SEC_1 = new Long(86400, 0);
    this.REFRESH_AFTER_SEC_1 = new Long(43200, 0);
  }
  protoOf(Companion_1).get_TOKEN_TTL_SEC_icezbl_k$ = function () {
    return this.TOKEN_TTL_SEC_1;
  };
  protoOf(Companion_1).get_REFRESH_AFTER_SEC_oe2iv5_k$ = function () {
    return this.REFRESH_AFTER_SEC_1;
  };
  var Companion_instance_1;
  function Companion_getInstance_1() {
    if (Companion_instance_1 == null)
      new Companion_1();
    return Companion_instance_1;
  }
  function AuthToken(value, issuedAtEpochSec, ttlSec) {
    Companion_getInstance_1();
    ttlSec = ttlSec === VOID ? new Long(86400, 0) : ttlSec;
    this.value_1 = value;
    this.issuedAtEpochSec_1 = issuedAtEpochSec;
    this.ttlSec_1 = ttlSec;
  }
  protoOf(AuthToken).get_value_j01efc_k$ = function () {
    return this.value_1;
  };
  protoOf(AuthToken).get_issuedAtEpochSec_jar73_k$ = function () {
    return this.issuedAtEpochSec_1;
  };
  protoOf(AuthToken).get_ttlSec_k4n4r2_k$ = function () {
    return this.ttlSec_1;
  };
  protoOf(AuthToken).remainingSec_rrxxar_k$ = function (nowEpochSec) {
    return this.issuedAtEpochSec_1.plus_r93sks_k$(this.ttlSec_1).minus_mfbszm_k$(nowEpochSec);
  };
  protoOf(AuthToken).shouldRefresh_1u6opk_k$ = function (nowEpochSec, refreshAfterSec) {
    return nowEpochSec.minus_mfbszm_k$(this.issuedAtEpochSec_1).compareTo_9jj042_k$(refreshAfterSec) >= 0 || this.remainingSec_rrxxar_k$(nowEpochSec).compareTo_9jj042_k$(refreshAfterSec) <= 0;
  };
  protoOf(AuthToken).shouldRefresh$default_uxnj2n_k$ = function (nowEpochSec, refreshAfterSec, $super) {
    refreshAfterSec = refreshAfterSec === VOID ? new Long(43200, 0) : refreshAfterSec;
    return $super === VOID ? this.shouldRefresh_1u6opk_k$(nowEpochSec, refreshAfterSec) : $super.shouldRefresh_1u6opk_k$.call(this, nowEpochSec, refreshAfterSec);
  };
  protoOf(AuthToken).component1_7eebsc_k$ = function () {
    return this.value_1;
  };
  protoOf(AuthToken).component2_7eebsb_k$ = function () {
    return this.issuedAtEpochSec_1;
  };
  protoOf(AuthToken).component3_7eebsa_k$ = function () {
    return this.ttlSec_1;
  };
  protoOf(AuthToken).copy_wq7x7_k$ = function (value, issuedAtEpochSec, ttlSec) {
    return new AuthToken(value, issuedAtEpochSec, ttlSec);
  };
  protoOf(AuthToken).copy$default_v1idfy_k$ = function (value, issuedAtEpochSec, ttlSec, $super) {
    value = value === VOID ? this.value_1 : value;
    issuedAtEpochSec = issuedAtEpochSec === VOID ? this.issuedAtEpochSec_1 : issuedAtEpochSec;
    ttlSec = ttlSec === VOID ? this.ttlSec_1 : ttlSec;
    return $super === VOID ? this.copy_wq7x7_k$(value, issuedAtEpochSec, ttlSec) : $super.copy_wq7x7_k$.call(this, value, issuedAtEpochSec, ttlSec);
  };
  protoOf(AuthToken).toString = function () {
    return 'AuthToken(value=' + this.value_1 + ', issuedAtEpochSec=' + this.issuedAtEpochSec_1.toString() + ', ttlSec=' + this.ttlSec_1.toString() + ')';
  };
  protoOf(AuthToken).hashCode = function () {
    var result = getStringHashCode(this.value_1);
    result = imul(result, 31) + this.issuedAtEpochSec_1.hashCode() | 0;
    result = imul(result, 31) + this.ttlSec_1.hashCode() | 0;
    return result;
  };
  protoOf(AuthToken).equals = function (other) {
    if (this === other)
      return true;
    if (!(other instanceof AuthToken))
      return false;
    var tmp0_other_with_cast = other instanceof AuthToken ? other : THROW_CCE();
    if (!(this.value_1 === tmp0_other_with_cast.value_1))
      return false;
    if (!this.issuedAtEpochSec_1.equals(tmp0_other_with_cast.issuedAtEpochSec_1))
      return false;
    if (!this.ttlSec_1.equals(tmp0_other_with_cast.ttlSec_1))
      return false;
    return true;
  };
  function WireGuardLease(peerIp, serverPublicKey, endpoint) {
    this.peerIp = peerIp;
    this.serverPublicKey = serverPublicKey;
    this.endpoint = endpoint;
  }
  protoOf(WireGuardLease).get_peerIp_i03voy_k$ = function () {
    return this.peerIp;
  };
  protoOf(WireGuardLease).get_serverPublicKey_8wx4cq_k$ = function () {
    return this.serverPublicKey;
  };
  protoOf(WireGuardLease).get_endpoint_30bvdu_k$ = function () {
    return this.endpoint;
  };
  protoOf(WireGuardLease).component1_7eebsc_k$ = function () {
    return this.peerIp;
  };
  protoOf(WireGuardLease).component2_7eebsb_k$ = function () {
    return this.serverPublicKey;
  };
  protoOf(WireGuardLease).component3_7eebsa_k$ = function () {
    return this.endpoint;
  };
  protoOf(WireGuardLease).copy_nc7k0r_k$ = function (peerIp, serverPublicKey, endpoint) {
    return new WireGuardLease(peerIp, serverPublicKey, endpoint);
  };
  protoOf(WireGuardLease).copy = function (peerIp, serverPublicKey, endpoint, $super) {
    peerIp = peerIp === VOID ? this.peerIp : peerIp;
    serverPublicKey = serverPublicKey === VOID ? this.serverPublicKey : serverPublicKey;
    endpoint = endpoint === VOID ? this.endpoint : endpoint;
    return this.copy_nc7k0r_k$(peerIp, serverPublicKey, endpoint);
  };
  protoOf(WireGuardLease).toString = function () {
    return 'WireGuardLease(peerIp=' + this.peerIp + ', serverPublicKey=' + this.serverPublicKey + ', endpoint=' + this.endpoint + ')';
  };
  protoOf(WireGuardLease).hashCode = function () {
    var result = getStringHashCode(this.peerIp);
    result = imul(result, 31) + getStringHashCode(this.serverPublicKey) | 0;
    result = imul(result, 31) + getStringHashCode(this.endpoint) | 0;
    return result;
  };
  protoOf(WireGuardLease).equals = function (other) {
    if (this === other)
      return true;
    if (!(other instanceof WireGuardLease))
      return false;
    var tmp0_other_with_cast = other instanceof WireGuardLease ? other : THROW_CCE();
    if (!(this.peerIp === tmp0_other_with_cast.peerIp))
      return false;
    if (!(this.serverPublicKey === tmp0_other_with_cast.serverPublicKey))
      return false;
    if (!(this.endpoint === tmp0_other_with_cast.endpoint))
      return false;
    return true;
  };
  function WireGuard() {
    WireGuard_instance = this;
  }
  protoOf(WireGuard).allowedIps = function (excludeLan) {
    var tmp;
    if (!excludeLan) {
      tmp = listOf(['0.0.0.0/0', '::/0']);
    } else {
      tmp = listOf(['0.0.0.0/1', '128.0.0.0/1', '::/0']);
    }
    return tmp;
  };
  protoOf(WireGuard).lanBypassList = function () {
    return listOf(['10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16']);
  };
  protoOf(WireGuard).buildConf_ipgxqq_k$ = function (privateKey, address, dns, lease, excludeLan) {
    var allowed = joinToString(this.allowedIps(excludeLan), ', ');
    return trimMargin('\n            |[Interface]\n            |Address = ' + address + '\n            |PrivateKey = ' + privateKey + '\n            |DNS = ' + dns + '\n            |\n            |[Peer]\n            |PublicKey = ' + lease.serverPublicKey + '\n            |Endpoint = ' + lease.endpoint + '\n            |AllowedIPs = ' + allowed + '\n            |PersistentKeepalive = 25\n            |\n        ');
  };
  protoOf(WireGuard).buildConf = function (privateKey, address, dns, lease, excludeLan, $super) {
    excludeLan = excludeLan === VOID ? true : excludeLan;
    return this.buildConf_ipgxqq_k$(privateKey, address, dns, lease, excludeLan);
  };
  var WireGuard_instance;
  function WireGuard_getInstance() {
    if (WireGuard_instance == null)
      new WireGuard();
    return WireGuard_instance;
  }
  //region block: post-declaration
  defineProp(protoOf(ConnectionState), 'name', protoOf(ConnectionState).get_name_woqyms_k$);
  defineProp(protoOf(ConnectionState), 'ordinal', protoOf(ConnectionState).get_ordinal_ip24qg_k$);
  defineProp(protoOf(TrafficVerdict), 'name', protoOf(TrafficVerdict).get_name_woqyms_k$);
  defineProp(protoOf(TrafficVerdict), 'ordinal', protoOf(TrafficVerdict).get_ordinal_ip24qg_k$);
  //endregion
  //region block: exports
  function $jsExportAll$(_) {
    var $pia = _.pia || (_.pia = {});
    var $pia$core = $pia.core || ($pia.core = {});
    defineProp($pia$core, 'JsTimeBridge', JsTimeBridge_getInstance);
    defineProp($pia$core, 'JsConstants', JsConstants_getInstance);
    var $pia = _.pia || (_.pia = {});
    var $pia$core = $pia.core || ($pia.core = {});
    $pia$core.ConnectionState = ConnectionState;
    $pia$core.ConnectionState.values = values;
    $pia$core.ConnectionState.valueOf = valueOf;
    defineProp($pia$core.ConnectionState, 'CONNECTED', ConnectionState_CONNECTED_getInstance);
    defineProp($pia$core.ConnectionState, 'CONNECTING', ConnectionState_CONNECTING_getInstance);
    defineProp($pia$core.ConnectionState, 'DISCONNECTED', ConnectionState_DISCONNECTED_getInstance);
    defineProp($pia$core.ConnectionState, 'DEGRADED', ConnectionState_DEGRADED_getInstance);
    $pia$core.TrafficVerdict = TrafficVerdict;
    $pia$core.TrafficVerdict.values = values_0;
    $pia$core.TrafficVerdict.valueOf = valueOf_0;
    defineProp($pia$core.TrafficVerdict, 'ALLOW_ALL', TrafficVerdict_ALLOW_ALL_getInstance);
    defineProp($pia$core.TrafficVerdict, 'ALLOW_LAN_ONLY', TrafficVerdict_ALLOW_LAN_ONLY_getInstance);
    defineProp($pia$core.TrafficVerdict, 'BLOCK_ALL', TrafficVerdict_BLOCK_ALL_getInstance);
    defineProp($pia$core, 'KillSwitch', KillSwitch_getInstance);
    var $pia = _.pia || (_.pia = {});
    var $pia$core = $pia.core || ($pia.core = {});
    $pia$core.WireGuardLease = WireGuardLease;
    defineProp($pia$core, 'WireGuard', WireGuard_getInstance);
  }
  $jsExportAll$(_);
  //endregion
  return _;
}));

//# sourceMappingURL=pia-core.js.map
