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
