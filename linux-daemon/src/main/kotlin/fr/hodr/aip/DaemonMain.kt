package fr.hodr.aip

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * aipd entry point. Modes (single tunnel = first stanza, compat):
 *   serve              run the daemon: socket server + link monitor
 *   connect [server]   dial the default tunnel now
 *   disconnect         keep the default tunnel down
 *   up <name> [server]    bring one tunnel up (merges kill switch)
 *   down <name>        bring one tunnel down (re-merges, stays closed)
 *   login user pass    store credentials in the 0600 token store
 *   pf on [hook]       enable port forwarding, optional Post-Port-Up command
 *   pf off             disable port forwarding
 *   port               print the bound forwarded port (or -)
 *   status             print one line per tunnel
 *
 * Tunnels come from AIPD_TUNNELS, /etc/aipd/tunnels.conf, or the state
 * dir's tunnels.conf. No config file means one default tunnel, exactly
 * the old behaviour. State dir defaults to /var/lib/aipd, overridable
 * with AIPD_STATE_DIR (tests, portable installs).
 */
fun main(args: Array<String>) {
    val dir = Paths.get(System.getenv("AIPD_STATE_DIR") ?: "/var/lib/aipd")
    val mgr = loadManager(dir)
    val engine = mgr.default.engine
    val store = FileTokenStore(dir.resolve("auth"))
    when (args.firstOrNull() ?: "status") {
        "serve" -> serve(mgr, store, dir)
        "connect" -> {
            val ok = engine.connect(args.getOrNull(1))
            println(if (ok) "up ${engine.activeServerId}" else "down: ${engine.lastError}")
            if (!ok) kotlin.system.exitProcess(1)
        }
        "disconnect" -> {
            engine.disconnect()
            println("down")
        }
        "up" -> {
            val name = args.getOrNull(1)
            if (name.isNullOrEmpty()) {
                System.err.println("usage: aipd up <tunnel> [serverId]")
                kotlin.system.exitProcess(2)
            }
            val ok = mgr.up(name, args.getOrNull(2))
            val e = mgr.tunnels[name]?.engine
            println(if (ok) "up ${e?.activeServerId}" else "down: ${e?.lastError ?: "unknown tunnel"}")
            if (!ok) kotlin.system.exitProcess(1)
        }
        "down" -> {
            val name = args.getOrNull(1)
            if (name.isNullOrEmpty() || !mgr.down(name)) {
                System.err.println("usage: aipd down <tunnel>")
                kotlin.system.exitProcess(2)
            }
            println("down $name")
        }
        "login" -> {
            val user = args.getOrNull(1)
            val pass = args.getOrNull(2)
            if (user.isNullOrEmpty() || pass.isNullOrEmpty()) {
                System.err.println("usage: aipd login user pass")
                kotlin.system.exitProcess(2)
            }
            store.saveCredentials(user, pass)
            println("credentials stored")
        }
        "status" -> mgr.statusLines().forEach(::println)
        "port" -> {
            val name = args.getOrNull(1) ?: mgr.default.spec.name
            println("port=${mgr.tunnels[name]?.engine?.forwardedPort ?: "-"}")
        }
        "pf" -> {
            // aipd pf on [hook]            -> default tunnel (compat)
            // aipd pf <tunnel> on [hook]   -> named tunnel, hook may hold spaces
            val (target, sub, hook) = if (args.getOrNull(1)?.lowercase() in listOf("on", "off")) {
                Triple(engine, args.getOrNull(1), args.drop(2).joinToString(" ").ifEmpty { null })
            } else {
                val named = mgr.tunnels[args.getOrNull(1)]?.engine
                Triple(named, args.getOrNull(2), args.drop(3).joinToString(" ").ifEmpty { null })
            }
            if (target == null) {
                System.err.println("usage: aipd pf on [hook]|off, aipd pf <tunnel> on [hook]|off")
                kotlin.system.exitProcess(2)
            }
            when (sub?.lowercase()) {
                "on" -> {
                    target.setPortForward(true, hook)
                    println("port=${target.forwardedPort ?: "-"}")
                }
                "off" -> {
                    target.setPortForward(false, null)
                    println("port=-")
                }
                else -> {
                    System.err.println("usage: aipd pf on [hook]|off, aipd pf <tunnel> on [hook]|off")
                    kotlin.system.exitProcess(2)
                }
            }
        }
        else -> {
            System.err.println("usage: aipd [serve|connect [serverId]|disconnect|up <tunnel> [serverId]|down <tunnel>|login user pass|pf on [hook]|off|port|status]")
            kotlin.system.exitProcess(2)
        }
    }
}

private fun loadManager(dir: Path): TunnelManager {
    val candidates = listOf(
        System.getenv("AIPD_TUNNELS")?.let { Paths.get(it) },
        Paths.get("/etc/aipd/tunnels.conf"),
        dir.resolve("tunnels.conf"),
    ).filterNotNull()
    val file = candidates.firstOrNull { Files.isRegularFile(it) }
    val specs = if (file == null) {
        listOf(TunnelSpec("default"))
    } else {
        TunnelConfigs.parse(Files.readString(file))
    }
    if (specs.isEmpty()) {
        System.err.println("no [tunnel.*] in ${file ?: "<default>"}")
        kotlin.system.exitProcess(2)
    }
    return TunnelManager(specs, dir)
}

private fun serve(mgr: TunnelManager, store: FileTokenStore, dir: Path) {
    val runner = ProcessCommandRunner()
    val socketPath = Paths.get(System.getenv("AIPD_SOCKET") ?: "/run/aipd/aipd.sock")
    Files.createDirectories(socketPath.parent)
    val server = AipdServer(mgr.default.engine, store, socketPath, mgr)
    server.start()
    mgr.restore()
    while (true) {
        try {
            Thread.sleep(15_000)
        } catch (interrupted: InterruptedException) {
            break
        }
        try {
            mgr.tick(linkUp(runner))
        } catch (t: Throwable) {
            System.err.println("monitor tick failed: ${t.message}")
        }
    }
    server.stop()
}

private fun linkUp(runner: CommandRunner): Boolean =
    runner.run(listOf("ip", "route", "get", "8.8.8.8")).ok
