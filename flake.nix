{
  description = "AIP VPN client: privileged daemon plus Qt GUI";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAll = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      packages = forAll (pkgs: {
        aip-daemon = pkgs.callPackage ./nix/packages/aip-daemon.nix { };
        aip-gui = pkgs.callPackage ./nix/packages/aip-gui.nix { };
        default = self.packages.${pkgs.system}.aip-daemon;
      });

      nixosModules.aip-client = import ./nix/modules/aip-client.nix;

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
