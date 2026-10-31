import com.posthog.PostHog
import com.posthog.PostHogConfig
import com.posthog.PostHogInterface
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

private val startedAt = System.currentTimeMillis()

private fun log(line: String) = println("%6d ms  %s".format(System.currentTimeMillis() - startedAt, line))

/** Prints every request the SDK makes, with the events and distinct ids in its body. */
private fun startServer(): String {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.executor = Executors.newCachedThreadPool()
    server.createContext("/") { exchange ->
        val fromSdk = exchange.requestHeaders.getFirst("User-Agent").orEmpty().startsWith("posthog")
        val raw = exchange.requestBody.readBytes()
        val gzip = exchange.requestHeaders.getFirst("Content-Encoding") == "gzip"
        val body = String(if (gzip) GZIPInputStream(raw.inputStream()).readBytes() else raw)
        val events = Regex("\"event\":\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()
        val ids = Regex("\"distinct_id\":\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.distinct().toList()
        val details = listOfNotNull(events.takeIf { it.isNotEmpty() }?.let { "events=$it" }, ids.takeIf { it.isNotEmpty() }?.let { "distinct_id=$it" })
        if (fromSdk) log("  -> ${exchange.requestMethod} ${exchange.requestURI.path} ${details.joinToString(" ")}")
        val reply = """{"featureFlags":{},"flags":{},"status":1}""".toByteArray()
        exchange.sendResponseHeaders(200, reply.size.toLong())
        exchange.responseBody.use { it.write(reply) }
    }
    server.start()
    return "http://127.0.0.1:${server.address.port}"
}

private fun postHog(
    host: String,
    optedOut: Boolean,
): PostHogInterface =
    PostHog.with(
        PostHogConfig(projectToken = "phc_repro", host = host).apply {
            optOut = optedOut
            preloadFeatureFlags = false
            flushAt = 100
            flushIntervalSeconds = 1
            storagePrefix = Files.createTempDirectory("posthog-queue").toString()
        },
    )

fun main() {
    val host = startServer()

    println("1. A user who never opted in: identify() and reset() while opted out")
    val first = postHog(host, optedOut = true)
    log("isOptOut() = ${first.isOptOut()}")
    first.identify("user-a")
    log("identify(\"user-a\")")
    first.reset()
    log("reset()")
    Thread.sleep(1_500)
    first.close()

    println()
    println("2. A user opts out after an event was captured")
    val second = postHog(host, optedOut = false)
    second.identify("user-b")
    second.capture("Lesson Started")
    log("capture(\"Lesson Started\") as user-b")
    second.optOut()
    log("optOut(), isOptOut() = ${second.isOptOut()}")
    Thread.sleep(3_000)
    second.close()

    System.exit(0)
}
