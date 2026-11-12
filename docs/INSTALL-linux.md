# Linux client install (Nix, any systemd distro)

No Flatpak: the kill switch needs host root and host networking, so the
daemon ships as a Nix package plus systemd units. The GUI is an
unprivileged Qt6 app talking to the daemon over `/run/aipd/aipd.sock`.

## 1. Install the daemon

```sh
nix profile add github:hodr-bot/aip#aip-daemon
sudo install -m 644 \
  "$(nix build github:hodr-bot/aip#aip-daemon --print-out-paths)/share/aipd.service" \
  /etc/systemd/system/aip-client.service
```

NixOS instead: add `self.nixosModules.aip-client` and set
`services.aip-client.enable = true` (see `nix/modules/aip-client.nix`
for `credentialsFile`, `killSwitch`, `allowLan`, `autoConnect`).

## 2. Credentials (never in git)

```sh
sudo sh -c 'printf "AIPD_USER=%s\nAIPD_PASS=%s\n" "pXXXXXXX" "secret" > /etc/aip-client.env'
sudo chmod 600 /etc/aip-client.env
sudo AIPD_STATE_DIR=/var/lib/aipd aipd login "$AIPD_USER" "$AIPD_PASS"
```

`aipd login` writes `/var/lib/aipd/auth` (0600, root only). The lease
cache in `/var/lib/aipd` holds no secrets.

## 3. Start and verify

```sh
sudo systemctl enable --now aip-client
aipd status            # or: echo STATUS | socat - UNIX:/run/aipd/aipd.sock
curl https://api.ipify.org   # should print the VPN exit IP
```

Leak check with the tunnel down (`aipd disconnect` keeps fail-closed
rules when killSwitch is on):

```sh
ping -c2 -W2 8.8.8.8            # must fail
dig +short +time=2 example.com  # must fail
nft list table inet aip         # drop policies, only the endpoint hole
```

## 4. GUI

```sh
nix profile add github:hodr-bot/aip#aip-gui
aip-gui
```

Add your user to group `pia` so the GUI can reach the socket, then
log in again. Reboot test: after a reboot `aipd status` must show Up
without any tap (unless you explicitly disconnected before).

## 5. Updates

```sh
nix profile upgrade aip-daemon aip-gui
sudo systemctl daemon-reload && sudo systemctl restart aip-client
```

Rollback is the previous Nix generation; NixOS uses `nixos-rebuild switch --rollback`.

## 6. Port forwarding

One command, hook optional:

```sh
aipd pf on                                    # default tunnel, print the port
aipd pf on "/usr/local/bin/qbit-port.sh"      # hook, AIPD_PORT set on every (re)bind
aipd pf boulot on "/usr/local/bin/qbit-port.sh"
aipd port          # port=- when disabled
aipd pf off
```

The bind rides on the connected gateway (`getSignature` on port 1337,
`bindPort` on 19999) and refreshes every 15 minutes. A failed bind never
drops the tunnel — the port just reports `-` with the error in `status`.
Socket: `PORTFORWARD [name] on [hook]|off`, `PORT [name]`.

## 7. Multiple tunnels

Copy `linux-daemon/tunnels.conf.example` to `/etc/aipd/tunnels.conf`
(or `$AIPD_STATE_DIR/tunnels.conf`, or point `AIPD_TUNNELS` at it):

```ini
[tunnel.boulot]
server = nl_amsterdam
port-forward = true
post-port-up = /usr/local/bin/qbit-port.sh

[tunnel.perso]
server = ch_zurich
auto-connect = false
```

Each tunnel gets `aip-<name>`, its own route table and fwmark, its own
state dir — keys and leases never cross. The kill switch stays merged:
one `nft -f` covers every live tunnel, and the last one going down
leaves the closed table, never a hole.

```sh
aipd up boulot [server]     # bring one tunnel up
aipd down boulot            # bring it down, re-merge the rules
aipd status                 # one line per tunnel
```

Socket: `UP name [server]`, `DOWN name`, `LIST`
(`name=status:server:port;...`). NixOS: `services.aip-client.tunnels.<name>`
renders the file for you (see `nix/modules/aip-client.nix`).
