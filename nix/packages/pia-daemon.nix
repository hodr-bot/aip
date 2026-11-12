{ lib, stdenv, jdk17, gradle, makeWrapper, wireguard-tools, nftables, iproute2 }:

stdenv.mkDerivation {
  pname = "pia-daemon";
  version = "0.1.0";
  src = ../..;

  nativeBuildInputs = [ jdk17 gradle makeWrapper ];
  buildPhase = ''
    export ANDROID_HOME=${placeholder "out"}
    gradle :linux-daemon:installDist --offline \
      -Dorg.gradle.java.home=${jdk17} -Dorg.gradle.daemon=false || \
    gradle :linux-daemon:installDist \
      -Dorg.gradle.java.home=${jdk17} -Dorg.gradle.daemon=false
  '';
  installPhase = ''
    mkdir -p $out/bin $out/libexec
    cp -r linux-daemon/build/install/piad/* $out/libexec/
    makeWrapper $out/libexec/bin/piad $out/bin/piad \
      --prefix PATH : ${lib.makeBinPath [ wireguard-tools nftables iproute2 ]}
  '';

  meta = with lib; {
    description = "AIP PIA VPN daemon (WireGuard plus fail-closed nftables kill switch)";
    platforms = platforms.linux;
  };
}
