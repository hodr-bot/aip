{ config, lib, pkgs, ... }:

with lib;

let cfg = config.services.pia-client; in
{
  options.services.pia-client = {
    enable = mkEnableOption "AIP PIA VPN client daemon";
    package = mkOption {
      type = types.package;
      default = pkgs.callPackage ./../packages/pia-daemon.nix { };
      description = "piad package to run.";
    };
    interface = mkOption {
      type = types.str;
      default = "pia0";
      description = "WireGuard interface name (informational; piad uses pia0).";
    };
    credentialsFile = mkOption {
      type = types.path;
      example = "/etc/pia-client.env";
      description = "File holding PIA_USER and PIA_PASS (mode 0600, never in the store).";
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
      description = "Region id to dial at boot. Null means restore last server.";
    };
  };

  config = mkIf cfg.enable {
    assertions = [
      {
        assertion = cfg.killSwitch -> config.networking.nftables.enable;
        message = "services.pia-client killSwitch needs networking.nftables.enable.";
      }
    ];

    systemd.services.pia-client = {
      description = "AIP PIA VPN daemon";
      after = [ "network-pre.target" ];
      before = [ "network-online.target" ];
      wants = [ "network-pre.target" ];
      wantedBy = [ "multi-user.target" ];
      restartTriggers = [ cfg.package ];
      serviceConfig = {
        Type = "simple";
        ExecStart = "${cfg.package}/bin/piad serve"
          + optionalString (cfg.autoConnect != null) " connect ${cfg.autoConnect}";
        EnvironmentFile = cfg.credentialsFile;
        Restart = "on-failure";
        RestartSec = 5;
        CapabilityBoundingSet = "CAP_NET_ADMIN";
        AmbientCapabilities = "CAP_NET_ADMIN";
        ProtectSystem = "strict";
        ProtectHome = true;
        PrivateTmp = true;
        NoNewPrivileges = true;
        StateDirectory = "piad";
        StateDirectoryMode = "0700";
      };
    };

    networking.nftables.ruleset = mkIf cfg.killSwitch ''
      table inet pia {
        chain input { type filter hook input priority 0; policy drop;
          iif "lo" accept; ct state established,related accept; iifname "pia0" accept; }
        chain output { type filter hook output priority 0; policy drop;
          oif "lo" accept; udp dport 67 accept;
          oifname "pia0" accept; }
        chain forward { type filter hook forward priority 0; policy drop; }
      }
    '';
  };
}
