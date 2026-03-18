package io.doloc.intellij.test

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TestHttpServer {
    private val responses = LinkedBlockingQueue<TestResponse>()
    private val requests = LinkedBlockingQueue<RecordedRequest>()
    private val server = HttpServer.create(InetSocketAddress(0), 0).apply {
        createContext("/") { exchange -> handle(exchange) }
    }

    val baseUrl: String
        get() = "http://127.0.0.1:${server.address.port}"

    fun start() {
        server.start()
    }

    fun enqueue(response: TestResponse) {
        responses.put(response)
    }

    fun takeRequest(timeout: Long, unit: TimeUnit): RecordedRequest? = requests.poll(timeout, unit)

    fun shutdown() {
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use { httpExchange ->
            val requestBody = httpExchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val requestPath = buildString {
                append(httpExchange.requestURI.rawPath)
                httpExchange.requestURI.rawQuery?.let {
                    append('?')
                    append(it)
                }
            }
            requests.put(
                RecordedRequest(
                    method = httpExchange.requestMethod,
                    path = requestPath,
                    headers = httpExchange.requestHeaders.toMap(),
                    body = requestBody
                )
            )

            val response = responses.poll() ?: TestResponse(statusCode = 500, body = "No queued response")
            val responseBytes = response.body.toByteArray(StandardCharsets.UTF_8)
            response.headers.forEach { (name, value) ->
                httpExchange.responseHeaders.add(name, value)
            }
            httpExchange.sendResponseHeaders(response.statusCode, responseBytes.size.toLong())
            httpExchange.responseBody.write(responseBytes)
        }
    }
}

data class TestResponse(
    val statusCode: Int,
    val body: String = "",
    val headers: Map<String, String> = emptyMap()
)

data class RecordedRequest(
    val method: String,
    val path: String,
    val headers: Map<String, List<String>>,
    val body: String
) {
    fun getHeader(name: String): String? = headers.entries
        .firstOrNull { (headerName, _) -> headerName.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()
}
