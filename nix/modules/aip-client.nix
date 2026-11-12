{ config, lib, pkgs, ... }:

with lib;

let cfg = config.services.aip-client; in
{
  options.services.aip-client = {
    enable = mkEnableOption "AIP VPN client daemon";
    package = mkOption {
      type = types.package;
      default = pkgs.callPackage ./../packages/aip-daemon.nix { };
      description = "aipd package to run.";
    };
    interface = mkOption {
      type = types.str;
      default = "aip0";
      description = "Legacy informational value. Real interfaces are aip-<tunnel> (aip-default with no tunnels.conf).";
    };
    credentialsFile = mkOption {
      type = types.path;
      example = "/etc/aip-client.env";
      description = "File holding AIPD_USER and AIPD_PASS (mode 0600, never in the store).";
    };
    killSwitch = mkOption {
      type = types.bool;
      default = true;
      description = "Fail closed: nftables drops everything outside the tunnel.";
    };
    allowLan = mkOption {
      type = types.bool;
      default = false;
      description = "Allow RFC1918 on the physical interface (bypass LAN).";
    };
    autoConnect = mkOption {
      type = types.nullOr types.str;
      default = null;
      example = "andorra";
      description = "Legacy single-tunnel hint. Prefer tunnels.<name>.autoConnect; this is ignored when tunnels is non-empty.";
    };
    tunnels = mkOption {
      type = types.attrsOf (types.submodule {
        options = {
          server = mkOption {
            type = types.nullOr types.str;
            default = null;
            example = "nl_amsterdam";
            description = "Region id to dial. Null restores the last server.";
          };
          portForward = mkOption {
            type = types.bool;
            default = false;
            description = "Enable provider port forwarding on this tunnel.";
          };
          allowLan = mkOption {
            type = types.bool;
            default = false;
            description = "Allow RFC1918 on the physical interface for this tunnel.";
          };
          autoConnect = mkOption {
            type = types.bool;
            default = true;
            description = "Dial this tunnel at boot and on link return.";
          };
          postPortUp = mkOption {
            type = types.nullOr types.str;
            default = null;
            example = "/usr/local/bin/qbit-port.sh";
            description = "Hook run with AIPD_PORT set on every (re)bind.";
          };
        };
      });
      default = { };
      example = {
        boulot = {
          server = "nl_amsterdam";
          portForward = true;
          postPortUp = "/usr/local/bin/qbit-port.sh";
        };
      };
      description = "Tunnels to manage, rendered to /etc/aipd/tunnels.conf. Empty means one default tunnel.";
    };
  };

  config = mkIf cfg.enable {
    assertions = [
      {
        assertion = cfg.killSwitch -> config.networking.nftables.enable;
        message = "services.aip-client killSwitch needs networking.nftables.enable.";
      }
      {
        assertion = builtins.all (n: builtins.match "[a-z0-9-]{1,11}" n != null) (builtins.attrNames cfg.tunnels);
        message = "services.aip-client tunnels names: a-z, 0-9, -, max 11 chars (interface aip-<name>).";
      }
    ];

    environment.etc."aipd/tunnels.conf" = mkIf (cfg.tunnels != { }) {
      mode = "0644";
      text = lib.concatStringsSep "\n" (
        lib.flatten (
          lib.mapAttrsToList (name: t: [
            "[tunnel.${name}]"
          ]
          ++ lib.optional (t.server != null) "server = ${t.server}"
          ++ lib.optional t.portForward "port-forward = true"
          ++ lib.optional t.allowLan "allow-lan = true"
          ++ lib.optional (!t.autoConnect) "auto-connect = false"
          ++ lib.optional (t.postPortUp != null) "post-port-up = ${t.postPortUp}"
          ++ [ "" ]) cfg.tunnels
        )
      );
    };

    systemd.services.aip-client = {
      description = "AIP VPN daemon";
      after = [ "network-pre.target" ];
      before = [ "network-online.target" ];
      wants = [ "network-pre.target" ];
      wantedBy = [ "multi-user.target" ];
      restartTriggers = [ cfg.package ];
      serviceConfig = {
        Type = "simple";
        ExecStart = "${cfg.package}/bin/aipd serve";
        EnvironmentFile = cfg.credentialsFile;
        Restart = "on-failure";
        RestartSec = 5;
        CapabilityBoundingSet = "CAP_NET_ADMIN";
        AmbientCapabilities = "CAP_NET_ADMIN";
        ProtectSystem = "strict";
        ProtectHome = true;
        PrivateTmp = true;
        NoNewPrivileges = true;
        StateDirectory = "aipd";
        StateDirectoryMode = "0700";
      };
    };

    networking.nftables.ruleset =
      let
        names = if cfg.tunnels == { } then [ "default" ] else builtins.attrNames cfg.tunnels;
        sel = if builtins.length names == 1
          then "\"aip-${builtins.head names}\""
          else "{ ${lib.concatMapStringsSep ", " (n: "\"aip-${n}\"") names} }";
      in
      mkIf cfg.killSwitch ''
        table inet aip {
          chain input { type filter hook input priority 0; policy drop;
            iif "lo" accept; ct state established,related accept; iifname ${sel} accept; }
          chain output { type filter hook output priority 0; policy drop;
            oif "lo" accept; udp dport 67 accept;
            oifname ${sel} accept; }
          chain forward { type filter hook forward priority 0; policy drop; }
        }
      '';
  };
}
