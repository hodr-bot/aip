{ lib, stdenv, python3, python3Packages, qt6, makeWrapper }:

stdenv.mkDerivation {
  pname = "pia-gui";
  version = "0.1.0";
  src = ../..;

  nativeBuildInputs = [ makeWrapper ];
  propagatedBuildInputs = [ python3Packages.pyside6 ];

  installPhase = ''
    mkdir -p $out/share/pia-gui $out/bin
    cp ../../linux-gui/daemon_client.py ../../linux-gui/pia_gui.py $out/share/pia-gui/
    cp -r ../../linux-gui/qml $out/share/pia-gui/
    makeWrapper ${python3.withPackages (ps: [ ps.pyside6 ])}/bin/python $out/bin/pia-gui \
      --add-flags $out/share/pia-gui/pia_gui.py \
      --set QML_IMPORT_PATH ${qt6.qtdeclarative}/lib/qt-6/qml
  '';

  meta = with lib; {
    description = "AIP PIA VPN Qt6 GUI (talks to piad over its Unix socket)";
    platforms = platforms.linux;
  };
}
