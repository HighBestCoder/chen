package org.jumpserver.chen.web.config;

import org.jumpserver.chen.framework.ws.ConsoleWebSocketHandler;
import org.jumpserver.chen.framework.ws.DBConsoleWebsocketHandler;
import org.jumpserver.chen.framework.ws.SessionWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Objects;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    @org.springframework.context.annotation.Bean
    public ConsoleWebSocketHandler consoleWebSocketHandler() {
        return new ConsoleWebSocketHandler();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry
                .addHandler(consoleWebSocketHandler(), "/ws/console")
                .addHandler(new SessionWebSocketHandler(), "/ws/session")
                .addHandler(new DBConsoleWebsocketHandler(), "/ws/db-console")
                .addInterceptors(new ServletWebSocketHandshakeInterceptor())
                .setAllowedOrigins("*");
    }

    public static class ServletWebSocketHandshakeInterceptor implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
            var offered = request.getHeaders().get("Sec-WebSocket-Protocol");
            if (offered == null || offered.size() != 1 || offered.get(0).isBlank()
                    || offered.get(0).contains(",")) {
                response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                return false;
            }
            var token = offered.get(0).trim();
            var session = org.jumpserver.chen.framework.session.SessionManager.getSession(token);
            boolean sessionChannel = request.getURI().getPath().endsWith("/ws/session");
            if (session == null || (!sessionChannel && !session.isActive())
                    || (sessionChannel && session.getPacketIO() != null)) {
                response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                return false;
            }
            attributes.put("token", token);
            response.getHeaders().set("Sec-WebSocket-Protocol", token);
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
            //握手之后
        }
    }
}