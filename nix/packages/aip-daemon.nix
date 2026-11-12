{ lib, stdenv, kotlin, jre, makeWrapper, fetchurl, wireguard-tools, nftables, iproute2 }:

let
  junit = fetchurl {
    url = "https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar";
    hash = "sha256-jklbY0Rp1k+4rPo0laBly6zIoP/1XOHjEAe+TBbcV9M=";
  };
  hamcrest = fetchurl {
    url = "https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar";
    hash = "sha256-Zv3vkelzk0jfeglqo4SlaF9Oh1WEzOiThqekclHE2Ok=";
  };
in
stdenv.mkDerivation {
  pname = "aip-daemon";
  version = "0.1.0";
  src = ../..;

  nativeBuildInputs = [ kotlin makeWrapper ];

  buildPhase = ''
    KOTLINC=${kotlin}/bin/kotlinc
    KOTLIN_BASE=$(dirname $(dirname $(readlink -f $KOTLINC)))
    KTEST=$(find $KOTLIN_BASE -name kotlin-test.jar | head -1)
    KTESTJUNIT=$(find $KOTLIN_BASE -name kotlin-test-junit.jar | head -1)
    test -n "$KTEST" || (echo "kotlin-test.jar not found" >&2; exit 1)
    CORE_SRC=$(echo src/commonMain/kotlin/pia/core/*.kt)
    MAIN=$(echo linux-daemon/src/main/kotlin/fr/hodr/aip/*.kt)
    # @JsExport is common-only metadata for the JS build; strip it for the
    # JVM daemon compile (no repo change, transform lives in this derivation).
    mkdir -p core-stripped
    for f in $CORE_SRC; do
      sed -e '/^import kotlin\.js\.JsExport$/d' \
          -e '/^@file:OptIn(kotlin\.js\.ExperimentalJsExport::class)$/d' \
          -e '/^@JsExport$/d' "$f" > "core-stripped/$(basename $f)"
    done
    CORE=$(echo core-stripped/*.kt)
    kotlinc -Xmulti-platform -include-runtime $CORE $MAIN -nowarn -d aipd.jar
    # Unit tests: compile and run under JUnit4. Fails the build on red.
    TEST=$(echo linux-daemon/src/test/kotlin/fr/hodr/aip/*.kt)
    kotlinc -Xmulti-platform $TEST -nowarn -cp aipd.jar:$KTEST:$KTESTJUNIT:${junit}:${hamcrest} -d aipd-test.jar
    ${jre}/bin/java -cp aipd.jar:aipd-test.jar:$KTEST:$KTESTJUNIT:${junit}:${hamcrest} \
      org.junit.runner.JUnitCore \
      fr.hodr.aip.NftRulesTest \
      fr.hodr.aip.FileTokenStoreTest \
      fr.hodr.aip.TunnelOpsTest \
      fr.hodr.aip.DaemonEngineTest \
      fr.hodr.aip.PortForwardTest \
      fr.hodr.aip.TunnelConfigsTest \
      fr.hodr.aip.TunnelManagerTest \
      fr.hodr.aip.AipdServerTest
  '';
  installPhase = ''
    mkdir -p $out/bin $out/libexec $out/share
    cp aipd.jar $out/libexec/
    cp linux-daemon/aipd.service $out/share/
    makeWrapper ${jre}/bin/java $out/bin/aipd \
      --add-flags "-cp $out/libexec/aipd.jar fr.hodr.aip.DaemonMainKt" \
      --prefix PATH : ${lib.makeBinPath [ wireguard-tools nftables iproute2 ]}
  '';

  meta = with lib; {
    description = "AIP VPN daemon (WireGuard plus fail-closed nftables kill switch)";
    platforms = platforms.linux;
  };
}
