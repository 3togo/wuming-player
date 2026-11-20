package com.wuming.tv.desktop.net

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DesktopResourceCancellationTest {
    @Test fun cancelsAnActiveBodyReadBeforeTheServerFinishes() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val responseStarted = CountDownLatch(1)
        val finish = CountDownLatch(1)
        server.createContext("/guide") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 10)
                exchange.responseBody.write(1)
                exchange.responseBody.flush()
                responseStarted.countDown()
                finish.await(8, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        val worker = Executors.newSingleThreadExecutor()
        try {
            server.start()
            val cancellation = DesktopResourceCancellation()
            val request = worker.submit<Throwable?> {
                runCatching {
                    DesktopResourceLoader().loadResponse(
                        "http://127.0.0.1:${server.address.port}/guide", emptyMap(), cancellation,
                    )
                }.exceptionOrNull()
            }
            assertTrue(responseStarted.await(3, TimeUnit.SECONDS))
            cancellation.cancel()
            assertTrue(request.get(3, TimeUnit.SECONDS) is CancellationException)
        } finally {
            finish.countDown()
            server.stop(0)
            worker.shutdownNow()
        }
    }
}
