{ lib, stdenv, python3, python3Packages, qt6, makeWrapper }:

stdenv.mkDerivation {
  pname = "aip-gui";
  version = "0.1.0";
  src = ../..;

  nativeBuildInputs = [ makeWrapper ];
  propagatedBuildInputs = [ python3Packages.pyside6 ];

  installPhase = ''
    mkdir -p $out/share/aip-gui $out/bin
    cp linux-gui/daemon_client.py linux-gui/aip_gui.py $out/share/aip-gui/
    cp -r linux-gui/qml $out/share/aip-gui/
    makeWrapper ${python3.withPackages (ps: [ ps.pyside6 ])}/bin/python $out/bin/aip-gui \
      --add-flags $out/share/aip-gui/aip_gui.py \
      --set QML_IMPORT_PATH ${qt6.qtdeclarative}/lib/qt-6/qml
  '';

  meta = with lib; {
    description = "AIP VPN Qt6 GUI (talks to aipd over its Unix socket)";
    platforms = platforms.linux;
  };
}
