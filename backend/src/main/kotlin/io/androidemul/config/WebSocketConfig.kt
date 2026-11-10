package io.androidemul.config

import io.androidemul.ws.StreamWebSocketHandler
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean

@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val handler: StreamWebSocketHandler,
    @Value("\${app.allowed-origins:*}") private val allowedOrigins: List<String>,
) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/ws/stream/*").setAllowedOriginPatterns(*allowedOrigins.toTypedArray())
    }

    @Bean
    fun webSocketContainer() = ServletServerContainerFactoryBean().apply {
        setMaxTextMessageBufferSize(64 * 1024)
        setMaxBinaryMessageBufferSize(64 * 1024)
        setMaxSessionIdleTimeout(0)
    }
}
