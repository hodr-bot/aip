# Linux client install (Nix, any systemd distro)

No Flatpak: the kill switch needs host root and host networking, so the
daemon ships as a Nix package plus systemd units. The GUI is an
unprivileged Qt6 app talking to the daemon over `/run/piad/piad.sock`.

## 1. Install the daemon

```sh
nix profile add github:hodr-bot/aip#pia-daemon
sudo install -m 644 \
  "$(nix build github:hodr-bot/aip#pia-daemon --print-out-paths)/share/piad.service" \
  /etc/systemd/system/pia-client.service
```

NixOS instead: add `self.nixosModules.pia-client` and set
`services.pia-client.enable = true` (see `nix/modules/pia-client.nix`
for `credentialsFile`, `killSwitch`, `allowLan`, `autoConnect`).

## 2. Credentials (never in git)

```sh
sudo sh -c 'printf "PIA_USER=%s\nPIA_PASS=%s\n" "pXXXXXXX" "secret" > /etc/pia-client.env'
sudo chmod 600 /etc/pia-client.env
sudo PIAD_STATE_DIR=/var/lib/piad piad login "$PIA_USER" "$PIA_PASS"
```

`piad login` writes `/var/lib/piad/auth` (0600, root only). The lease
cache in `/var/lib/piad` holds no secrets.

## 3. Start and verify

```sh
sudo systemctl enable --now pia-client
piad status            # or: echo STATUS | socat - UNIX:/run/piad/piad.sock
curl https://api.ipify.org   # should print the VPN exit IP
```

Leak check with the tunnel down (`piad disconnect` keeps fail-closed
rules when killSwitch is on):

```sh
ping -c2 -W2 8.8.8.8            # must fail
dig +short +time=2 example.com  # must fail
nft list table inet pia         # drop policies, only the endpoint hole
```

## 4. GUI

```sh
nix profile add github:hodr-bot/aip#pia-gui
pia-gui
```

Add your user to group `pia` so the GUI can reach the socket, then
log in again. Reboot test: after a reboot `piad status` must show Up
without any tap (unless you explicitly disconnected before).

## 5. Updates

```sh
nix profile upgrade pia-daemon pia-gui
sudo systemctl daemon-reload && sudo systemctl restart pia-client
```

Rollback is the previous Nix generation; NixOS uses `nixos-rebuild switch --rollback`.

## 6. Port forwarding

One command, hook optional:

```sh
piad pf on                                    # default tunnel, print the port
piad pf on "/usr/local/bin/qbit-port.sh"      # hook, PIAD_PORT set on every (re)bind
piad pf boulot on "/usr/local/bin/qbit-port.sh"
piad port          # port=- when disabled
piad pf off
```

The bind rides on the connected gateway (`getSignature` on port 1337,
`bindPort` on 19999) and refreshes every 15 minutes. A failed bind never
drops the tunnel — the port just reports `-` with the error in `status`.
Socket: `PORTFORWARD [name] on [hook]|off`, `PORT [name]`.

## 7. Multiple tunnels

Copy `linux-daemon/tunnels.conf.example` to `/etc/piad/tunnels.conf`
(or `$PIAD_STATE_DIR/tunnels.conf`, or point `PIAD_TUNNELS` at it):

```ini
[tunnel.boulot]
server = nl_amsterdam
port-forward = true
post-port-up = /usr/local/bin/qbit-port.sh

[tunnel.perso]
server = ch_zurich
auto-connect = false
```

Each tunnel gets `pia-<name>`, its own route table and fwmark, its own
state dir — keys and leases never cross. The kill switch stays merged:
one `nft -f` covers every live tunnel, and the last one going down
leaves the closed table, never a hole.

```sh
piad up boulot [server]     # bring one tunnel up
piad down boulot            # bring it down, re-merge the rules
piad status                 # one line per tunnel
```

Socket: `UP name [server]`, `DOWN name`, `LIST`
(`name=status:server:port;...`). NixOS: `services.pia-client.tunnels.<name>`
renders the file for you (see `nix/modules/pia-client.nix`).
