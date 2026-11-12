{
  description = "AIP PIA VPN client: privileged daemon plus Qt GUI";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAll = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      packages = forAll (pkgs: {
        pia-daemon = pkgs.callPackage ./nix/packages/pia-daemon.nix { };
        pia-gui = pkgs.callPackage ./nix/packages/pia-gui.nix { };
        default = self.packages.${pkgs.system}.pia-daemon;
      });

      nixosModules.pia-client = import ./nix/modules/pia-client.nix;

      devShells = forAll (pkgs: {
        default = pkgs.mkShell {
          packages = with pkgs; [
            jdk17 gradle wireguard-tools nftables
            python3 qt6.qtdeclarative
            python3Packages.pyside6
          ];
        };
      });
    };
}
