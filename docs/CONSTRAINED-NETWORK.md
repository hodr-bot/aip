# Constrained networks: small-packet TLS for PIA region API

Some egress paths drop IP packets larger than ~1000 bytes. The default
TLS ClientHello (~1500 bytes) never reaches the server: the handshake
fails with an EOF while reading. Everything in this note was observed
live against `andorra.pvt.site:1337` from such a network.

## What works

- Keep the whole ClientHello under 1000 bytes: TLS 1.2 with a short
  cipher list (4 suites) and default extensions is about 250 bytes.
- TLS 1.2 only. TLS 1.3-only hellos are answered with
  `protocol_version`; TLS 1.2 with RSA-only suites is answered with
  `handshake_failure`.

## Cipher policy (live, 2026-11-07)

The region endpoint negotiates `TLSv1.2` with
`ECDHE-ECDSA-AES256-GCM-SHA384`. Offer ECDSA suites first:

- `TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384`
- `TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256`
- `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256` (fallback)
- `TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384` (fallback)

RSA-only hellos are rejected even though the CA is RSA: the leaf key
is ECDSA.

## Certificate rotation warning

`*.pvt.site` currently serves a Let's Encrypt wildcard, NOT the
`ca.rsa.4096.crt` pinned by `pia-foss/manual-connections`. The scripts
still send `--cacert ca.rsa.4096.crt`; on a fresh system that anchor no
longer validates the region endpoint. Platform adapters must use the
platform trust store (which carries the current Let's Encrypt
hierarchy) instead of the fossil CA file.

## Query encoding

`GET /addKey?pt=...&pubkey=...` must be percent-encoded. Base64url
values contain `+`, `/`, `=` which break server-side parsing and yield
HTTP 409. `PiaClient.addKey` encodes both parameters; this was found
via a live 409 and is covered by
`addKey percent-encodes token and pubkey in query`.

## Reference prober

`LiveProof` (scratch, not shipped) implements the small-hello fetch
with `javax.net.ssl.SSLSocket`: TLS 1.2, short cipher list, then a
SHA-256 leaf pin passed as `-Dpia.leafPin=...`. The core stays
untouched; only the platform `HttpPort` adapter constrains the TLS
stack, which is exactly where this belongs.

`LocalAddKeyStub` (scratch, not shipped) is a localhost TLS server on
127.0.0.1:1337 serving one canned `addKey` OK reply. Run it, then run
`LiveProof STUB x <pubkey>` with `-Dpia.leafPin=<stub cert sha256>`:
the core's URL building, percent-encoding and JSON parsing are
exercised end to end with zero PIA contact. Verified 2026-11-07: the
stub received
`GET /addKey?pt=dummy-token&pubkey=PUBKEY%3A...%2B...%3D` and the core
parsed back peer IP, server key (with raw `+/=` chars) and endpoint.
