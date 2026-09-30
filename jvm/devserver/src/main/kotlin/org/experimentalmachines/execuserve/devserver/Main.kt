package org.experimentalmachines.execuserve.devserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.experimentalmachines.execuserve.catalog.JavaFileSystem
import org.experimentalmachines.execuserve.catalog.ModelScanner
import org.experimentalmachines.execuserve.engine.Engine
import org.experimentalmachines.execuserve.engine.EngineConfig
import org.experimentalmachines.execuserve.engine.ModelEntry
import org.experimentalmachines.execuserve.engine.ModelFiles
import org.experimentalmachines.execuserve.engine.ModelSource
import org.experimentalmachines.execuserve.engine.StaticModelSource
import org.experimentalmachines.execuserve.server.ApiKey
import org.experimentalmachines.execuserve.server.BindMode
import org.experimentalmachines.execuserve.server.ExecuServer
import org.experimentalmachines.execuserve.server.ServerContext
import org.experimentalmachines.execuserve.server.ServerSettings
import org.experimentalmachines.execuserve.server.StaticKeys
import org.experimentalmachines.execuserve.testing.FakeRuntime
import java.io.File
import java.net.NetworkInterface
import java.util.concurrent.Executors
import kotlin.system.exitProcess

private const val USAGE = """
execuserve-dev: ExecuServe's HTTP server on this machine, over a scripted runtime.

  --model <file.pte>     serve one model (tokenizer beside it); only its name and family matter here
  --models <dir>         serve every model found in a directory, as the phone would
  --port <n>             default 8080
  --network              listen on every interface instead of loopback
  --key <secret>         the API key clients send as a bearer token (default: sk-dev)
  --open-loopback        let loopback clients in without a key
  --token-ms <n>         milliseconds per generated token (default 25)
  --prefill-ms <x>       milliseconds per prompt character read (default 0), for long prompts
  --window <n>           the scripted model's window, in characters (default 131072)

With no --model or --models, one scripted model named 'qwen3-1.7b' is served.
"""

fun main(args: Array<String>): Unit = runBlocking {
    val options = parse(args) ?: run {
        println(USAGE)
        exitProcess(2)
    }
    val runtime = FakeRuntime(window = options.window, prefillLength = 2_048, reply = ::scriptedReply, id = "scripted")
    runtime.tokenDelayMs = options.tokenMs
    runtime.prefillDelayPerCharMs = options.prefillMs
    val models: ModelSource = when {
        options.modelsDir != null -> ModelScanner(JavaFileSystem, options.modelsDir).also { it.rescan() }
        options.model != null -> {
            val file = File(options.model)
            ModelScanner(JavaFileSystem, file.parentFile.absolutePath).also { it.rescan() }
                .let { scanner -> StaticModelSource(scanner.all().filter { it.files.model == file.absolutePath }) }
        }
        else -> StaticModelSource(
            listOf(
                ModelEntry(
                    "qwen3-1.7b-scripted", ModelFiles("scripted.pte", "scripted.json"), "qwen3",
                    contextLength = options.window, aliases = setOf("qwen3-1.7b"),
                ),
            ),
        )
    }
    if (models.all().isEmpty()) {
        System.err.println("No models found (a .pte needs its tokenizer beside it).")
        exitProcess(1)
    }
    val lane = Executors.newSingleThreadExecutor { Thread(it, "execuserve-lane") }.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob())
    val engine = Engine(runtime, models, lane, scope, EngineConfig())
    engine.start()
    val settings = ServerSettings(
        port = options.port,
        bind = if (options.network) BindMode.NETWORK else BindMode.LOOPBACK,
        openLoopback = options.openLoopback,
    )
    val server = ExecuServer(
        ServerContext(
            engine = engine,
            settings = settings,
            keys = StaticKeys(listOf(ApiKey("dev", "dev key", options.key))),
            deviceHosts = ::localAddresses,
            version = "dev",
            nowSeconds = { System.currentTimeMillis() / 1000 },
        ),
    )
    server.start()
    println("ExecuServe (scripted runtime) listening on ${server.endpoints.joinToString(", ")}")
    println("Models: ${models.all().joinToString(", ") { it.id + if (it.aliases.isEmpty()) "" else " (" + it.aliases.joinToString() + ")" }}")
    println("Key: ${options.key}")
    Runtime.getRuntime().addShutdownHook(Thread { runBlocking { server.stop(); engine.stop(500) } })
    awaitCancellation()
}

private data class Options(
    val model: String? = null,
    val modelsDir: String? = null,
    val port: Int = 8080,
    val network: Boolean = false,
    val key: String = "sk-dev",
    val openLoopback: Boolean = false,
    val tokenMs: Long = 25,
    val prefillMs: Double = 0.0,
    val window: Int = 131_072,
)

private fun parse(args: Array<String>): Options? {
    var options = Options()
    var i = 0
    while (i < args.size) {
        val value = args.getOrNull(i + 1)
        options = when (args[i]) {
            "--model" -> options.copy(model = value ?: return null).also { i++ }
            "--models" -> options.copy(modelsDir = value ?: return null).also { i++ }
            "--port" -> options.copy(port = value?.toIntOrNull() ?: return null).also { i++ }
            "--key" -> options.copy(key = value ?: return null).also { i++ }
            "--token-ms" -> options.copy(tokenMs = value?.toLongOrNull() ?: return null).also { i++ }
            "--window" -> options.copy(window = value?.toIntOrNull() ?: return null).also { i++ }
            "--prefill-ms" -> options.copy(prefillMs = value?.toDoubleOrNull() ?: return null).also { i++ }
            "--network" -> options.copy(network = true)
            "--open-loopback" -> options.copy(openLoopback = true)
            else -> return null
        }
        i++
    }
    return options
}

private fun localAddresses(): Set<String> = NetworkInterface.getNetworkInterfaces().toList()
    .flatMap { it.inetAddresses.toList() }
    .map { it.hostAddress.substringBefore('%').lowercase() }
    .toSet()

/**
 * What the scripted model says: an echo of the last user turn, word by word, ending with
 * the family's end marker. When tools were offered and the user mentions the weather, it
 * calls the first tool instead, so agent loops can be exercised end to end.
 */
private fun scriptedReply(prompt: String): List<String> {
    val lastUser = prompt.substringAfterLast("<|im_start|>user\n", "").substringBefore("<|im_end|>")
    val marker = "<|im_end|>"
    val tool = Regex("\"name\": ?\"([^\"]+)\"").find(prompt.substringAfterLast("<tools>", "").substringBefore("</tools>"))
    if (tool != null && "weather" in lastUser.lowercase() && "<tool_response>" !in prompt.substringAfterLast("<|im_start|>user")) {
        return listOf("<tool_call>", "\n{\"name\": \"${tool.groupValues[1]}\", \"arguments\": {\"city\": \"Manila\"}}\n", "</tool_call>", marker)
    }
    val words = "You said: ${lastUser.trim().ifEmpty { "(nothing)" }}".split(" ")
    return words.mapIndexed { index, word -> if (index == 0) word else " $word" } + marker
}
