package fr.hodr.pia

import java.nio.file.Path
import java.nio.file.Paths
import pia.core.PiaClient
import pia.core.ServerCatalog

/**
 * piad entry point. Modes:
 *   serve              run the daemon: socket server + link monitor
 *   connect [server]   dial now (clears stop flag and cooldown)
 *   disconnect         stay down (persists the stop flag first)
 *   login user pass    store credentials in the 0600 token store
 *   pf on [hook]       enable port forwarding, optional Post-Port-Up command
 *   pf off             disable port forwarding
 *   port               print the bound forwarded port (or -)
 *   status             print one-line state
 *
 * State dir defaults to /var/lib/piad, overridable with PIAD_STATE_DIR
 * (tests, portable installs).
 */
fun main(args: Array<String>) {
    val dir = Paths.get(System.getenv("PIAD_STATE_DIR") ?: "/var/lib/piad")
    val http = LinuxHttpPort()
    val clock = SystemClock()
    val store = FileTokenStore(dir.resolve("auth"))
    val state = EngineState(dir.resolve("state.properties"))
    val runner = ProcessCommandRunner()
    val pf = PortForward(PiaClient(http, clock), clock) { port, _ ->
        state.postPortUp?.let { hook ->
            runner.runShell(hook, mapOf("PIAD_PORT" to port.toString()))
        }
    }
    val engine = DaemonEngine(
        client = PiaClient(http, clock),
        catalog = ServerCatalog(http),
        store = store,
        tunnel = TunnelOps(runner),
        state = state,
        clock = clock,
        pf = pf,
    )
    when (args.firstOrNull() ?: "status") {
        "serve" -> serve(engine, store, dir, runner)
        "connect" -> {
            val ok = engine.connect(args.getOrNull(1))
            println(if (ok) "up ${engine.activeServerId}" else "down: ${engine.lastError}")
            if (!ok) kotlin.system.exitProcess(1)
        }
        "disconnect" -> {
            engine.disconnect()
            println("down")
        }
        "login" -> {
            val user = args.getOrNull(1)
            val pass = args.getOrNull(2)
            if (user.isNullOrEmpty() || pass.isNullOrEmpty()) {
                System.err.println("usage: piad login user pass")
                kotlin.system.exitProcess(2)
            }
            store.saveCredentials(user, pass)
            println("credentials stored")
        }
        "status" -> println(
            "status=${engine.status} server=${engine.activeServerId ?: "-"} " +
                "port=${engine.forwardedPort ?: "-"} " +
                "error=${engine.lastError ?: "-"}",
        )
        "port" -> println("port=${engine.forwardedPort ?: "-"}")
        "pf" -> {
            when (args.getOrNull(1)?.lowercase()) {
                "on" -> {
                    engine.setPortForward(true, args.drop(2).joinToString(" ").ifEmpty { null })
                    println("port=${engine.forwardedPort ?: "-"}")
                }
                "off" -> {
                    engine.setPortForward(false, null)
                    println("port=-")
                }
                else -> {
                    System.err.println("usage: piad pf on [hook]|off")
                    kotlin.system.exitProcess(2)
                }
            }
        }
        else -> {
            System.err.println("usage: piad [serve|connect [serverId]|disconnect|login user pass|pf on [hook]|pf off|port|status]")
            kotlin.system.exitProcess(2)
        }
    }
}

private fun serve(engine: DaemonEngine, store: FileTokenStore, dir: Path, runner: CommandRunner) {
    val server = PiadServer(engine, store, dir.resolve("piad.sock"))
    server.start()
    engine.restore(linkUp = linkUp(runner))
    while (true) {
        try {
            Thread.sleep(15_000)
        } catch (interrupted: InterruptedException) {
            break
        }
        try {
            engine.tick(linkUp = linkUp(runner), handshakeSec = null)
        } catch (t: Throwable) {
            System.err.println("monitor tick failed: ${t.message}")
        }
    }
    server.stop()
}

private fun linkUp(runner: CommandRunner): Boolean =
    runner.run(listOf("ip", "route", "get", "8.8.8.8")).ok
