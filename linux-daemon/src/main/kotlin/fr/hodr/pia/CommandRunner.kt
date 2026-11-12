package fr.hodr.pia

/**
 * Shell seam: every privileged command goes through here so unit tests can
 * record and script them without root. The private key never appears in
 * argv: secrets travel on stdin only.
 */
interface CommandRunner {
    data class Result(val exit: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exit == 0
    }

    fun run(argv: List<String>, stdin: String? = null): Result
}

class ProcessCommandRunner : CommandRunner {
    override fun run(argv: List<String>, stdin: String?): CommandRunner.Result {
        val process = ProcessBuilder(argv).redirectErrorStream(false).start()
        if (stdin != null) {
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(stdin) }
        } else {
            process.outputStream.close()
        }
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = process.errorStream.readBytes().toString(Charsets.UTF_8)
        return CommandRunner.Result(process.waitFor(), out, err)
    }
}

/** Scripted runner for tests: canned replies, full command log. */
class FakeRunner(private val replies: Map<String, CommandRunner.Result> = emptyMap()) : CommandRunner {
    val commands = mutableListOf<Pair<List<String>, String?>>()
    var default = CommandRunner.Result(0, "", "")

    override fun run(argv: List<String>, stdin: String?): CommandRunner.Result {
        commands.add(argv to stdin)
        return replies[argv.firstOrNull()].takeIf { it != null } ?: default
    }
}
