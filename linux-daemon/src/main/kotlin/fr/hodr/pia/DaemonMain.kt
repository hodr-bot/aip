package fr.hodr.pia

/**
 * piad entry point: privileged daemon owning the WireGuard tunnel and the
 * nftables fail-closed kill switch. Unprivileged clients (GUI, CLI) talk to
 * it over D-Bus; this binary only parses argv for now.
 */
fun main(args: Array<String>) {
    val cmd = args.firstOrNull() ?: "status"
    when (cmd) {
        "connect" -> println("piad: connect not implemented yet")
        "disconnect" -> println("piad: disconnect not implemented yet")
        "status" -> println("piad: down (skeleton)")
        else -> {
            System.err.println("usage: piad [connect [serverId]|disconnect|status]")
            kotlin.system.exitProcess(2)
        }
    }
}
