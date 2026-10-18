package com.wuming.tv.desktop.cast

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.wuming.tv.core.upnp.OkHttpStreamClient
import org.fourthline.cling.DefaultUpnpServiceConfiguration
import org.fourthline.cling.model.message.Connection
import org.fourthline.cling.model.message.StreamRequestMessage
import org.fourthline.cling.model.message.StreamResponseMessage
import org.fourthline.cling.model.message.UpnpHeaders
import org.fourthline.cling.model.message.UpnpRequest
import org.fourthline.cling.transport.Router
import org.fourthline.cling.transport.spi.InitializationException
import org.fourthline.cling.transport.spi.NetworkAddressFactory
import org.fourthline.cling.transport.spi.StreamClient
import org.fourthline.cling.transport.spi.StreamServer
import org.fourthline.cling.transport.spi.StreamServerConfiguration
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress

/** Desktop transports use the JDK HTTP server and the already bundled OkHttp client. */
class DesktopUpnpConfiguration : DefaultUpnpServiceConfiguration() {
    override fun createStreamClient(): StreamClient<*> =
        OkHttpStreamClient(
            OkHttpStreamClient.Configuration(syncProtocolExecutorService),
            bypassProxy = true,
            maxBodyBytes = 1_048_576,
        )

    override fun createStreamServer(addresses: NetworkAddressFactory): StreamServer<*> = Server()

    internal class Server : StreamServer<StreamServerConfiguration> {
        private var server: HttpServer? = null

        override fun getConfiguration(): StreamServerConfiguration = StreamServerConfiguration { 0 }

        override fun getPort(): Int = checkNotNull(server) { "Desktop casting listener is not initialized" }.address.port

        @Throws(InitializationException::class)
        override fun init(address: InetAddress, router: Router) {
            try {
                server = HttpServer.create(InetSocketAddress(address, 0), 32).apply {
                    executor = router.configuration.streamServerExecutorService
                    createContext("/") { exchange -> handle(exchange, router) }
                }
            } catch (error: IOException) {
                throw InitializationException("Cannot start desktop casting listener", error)
            }
        }

        override fun run() {
            checkNotNull(server) { "Desktop casting listener is not initialized" }.start()
        }

        override fun stop() {
            server?.stop(0)
        }

        private fun handle(exchange: HttpExchange, router: Router) {
            object : org.fourthline.cling.transport.spi.UpnpStream(router.protocolFactory) {
                override fun run() {
                    try {
                        val request = StreamRequestMessage(
                            UpnpRequest.Method.getByHttpName(exchange.requestMethod),
                            exchange.requestURI,
                        )
                        request.headers = UpnpHeaders(exchange.requestHeaders)
                        request.connection = object : Connection {
                            override fun isOpen(): Boolean = true

                            override fun getRemoteAddress(): InetAddress = exchange.remoteAddress.address

                            override fun getLocalAddress(): InetAddress = exchange.localAddress.address
                        }
                        val body = exchange.requestBody.readNBytes(MAX_BODY_BYTES + 1)
                        if (body.size > MAX_BODY_BYTES) {
                            exchange.sendResponseHeaders(413, -1)
                            return
                        }
                        if (body.isNotEmpty()) request.setBodyCharacters(body)
                        val response = process(request)
                        if (response == null) {
                            exchange.sendResponseHeaders(404, -1)
                            return
                        }
                        sendResponse(exchange, response)
                        responseSent(response)
                    } catch (error: Exception) {
                        responseException(error)
                    } finally {
                        exchange.close()
                    }
                }
            }.run()
        }

        private fun sendResponse(exchange: HttpExchange, response: StreamResponseMessage) {
            response.headers.forEach { (key, values) ->
                if (!key.equals("Content-Length", ignoreCase = true)) {
                    exchange.responseHeaders[key] = values
                }
            }
            val result = if (response.hasBody()) response.bodyBytes else ByteArray(0)
            exchange.sendResponseHeaders(
                response.operation.statusCode,
                if (result.isEmpty()) -1 else result.size.toLong(),
            )
            if (result.isNotEmpty()) exchange.responseBody.write(result)
        }

        private companion object {
            const val MAX_BODY_BYTES = 1_048_576
        }
    }
}
