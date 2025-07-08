package io.doloc.intellij.http

import java.net.http.HttpClient
import java.time.Duration

/**
 * Provides a configured HttpClient for communicating with the doloc API.
 */
object HttpClientProvider {

    val client: HttpClient by lazy {
        createHttpClient()
    }

    private fun createHttpClient(): HttpClient {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }
}
