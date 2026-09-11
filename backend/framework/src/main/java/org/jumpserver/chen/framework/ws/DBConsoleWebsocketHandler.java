package org.jumpserver.chen.framework.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
public class DBConsoleWebsocketHandler extends TextWebSocketHandler {
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        super.afterConnectionEstablished(session);
    }


    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        var identity = org.jumpserver.chen.framework.session.SessionManager.getSession(
                (String) session.getAttributes().get("token"));
        if (identity == null || !identity.isActive()) {
            session.close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        super.handleTransportError(session, exception);
    }
}
