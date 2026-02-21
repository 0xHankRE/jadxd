package dev.jadxd

import dev.jadxd.core.SessionManager
import dev.jadxd.server.configureRoutes
import dev.jadxd.server.configureProtocolRoutes
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.slf4j.LoggerFactory
import java.nio.file.Path

fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("jadxd")

    // Simple arg parsing (no heavy CLI framework needed)
    val config = parseArgs(args)

    log.info("jadxd v0.1.0 starting on port {} (cache: {})", config.port, config.cacheDir)

    val sessionManager = SessionManager(config.cacheDir)

    val server = embeddedServer(Netty, port = config.port, host = config.host) {
        configureRoutes(sessionManager)
        configureProtocolRoutes(sessionManager)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("shutting down…")
        sessionManager.closeAll()
        server.stop(1000, 5000)
    })

    server.start(wait = true)
}

private data class ServerConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8085,
    val cacheDir: Path = Path.of(
        System.getenv("XDG_CACHE_HOME") ?: "${System.getProperty("user.home")}/.cache",
        "jadxd",
    ),
)

private fun parseArgs(args: Array<String>): ServerConfig {
    var host = "127.0.0.1"
    var port = 8085
    var cacheDir: String? = null

    val iter = args.iterator()
    while (iter.hasNext()) {
        when (val arg = iter.next()) {
            "--host" -> host = iter.next()
            "--port", "-p" -> port = iter.next().toInt()
            "--cache-dir" -> cacheDir = iter.next()
            "--help", "-h" -> {
                println("""
                    jadxd – JADX decompiler service

                    Usage: jadxd [OPTIONS]

                    Options:
                      --host HOST        Bind address (default: 127.0.0.1)
                      --port, -p PORT    Listen port (default: 8085)
                      --cache-dir DIR    Cache directory (default: ~/.cache/jadxd)
                      --help, -h         Show this help
                """.trimIndent())
                System.exit(0)
            }
            else -> {
                System.err.println("unknown argument: $arg")
                System.exit(1)
            }
        }
    }

    return ServerConfig(
        host = host,
        port = port,
        cacheDir = if (cacheDir != null) Path.of(cacheDir)
        else ServerConfig().cacheDir,
    )
}
