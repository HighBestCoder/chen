package org.jumpserver.chen.framework.ws;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.controller.dialog.Dialog;
import org.jumpserver.chen.framework.ws.io.Packet;
import org.jumpserver.chen.framework.ws.io.PacketIO;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
public class SessionWebSocketHandler extends TextWebSocketHandler {
    private boolean owns(Session session, WebSocketSession socket) {
        return session != null && session.getPacketIO() != null
                && session.getPacketIO().getWsSession() == socket;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        SessionManager.setContext((String) socket.getAttributes().get("token"));
        Session session = SessionManager.getCurrentSession();
        boolean claimed = false;
        try {
            if (session == null) {
                socket.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
            if (socket instanceof NativeWebSocketSession ns) {
                var nativeSession = ns.getNativeSession(jakarta.websocket.Session.class);
                if (nativeSession != null) {
                    nativeSession.getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", 90_000L);
                }
            }
            synchronized (session) {
                // Two handshakes may race; only one socket may activate this identity.
                if (session.getPacketIO() != null || SessionManager.getCurrentSession() != session) {
                    socket.close(CloseStatus.POLICY_VIOLATION);
                    return;
                }
                claimed = true;
                session.activeSession(new PacketIO(socket));
            }
            Dialog dialog = new Dialog(MessageUtils.get("msg.dialog.title.init_datasource"));
            dialog.setBody(MessageUtils.get("msg.dialog.message.init_datasource"));
            session.getController().showDialog(dialog);
            try {
                session.getDatasource().ping();
                session.getDatasource().init();
            } catch (Exception e) {
                dialog.setTitle(MessageUtils.get("msg.dialog.title.init_datasource_failed"));
                // Driver errors are untrusted text, never HTML.
                dialog.setBodyType("text");
                dialog.setBody(MessageUtils.get("msg.dialog.message.init_datasource_failed") + ": " + e.getMessage());
                session.getController().showDialog(dialog);
                session.close();
                return;
            }
            if (!session.isActive()) return;
            session.getController().closeDialog();
            session.getPacketIO().sendPacket("set_ready", null);
        } catch (Exception e) {
            if (claimed) session.close();
            socket.close(CloseStatus.SERVER_ERROR);
            throw e;
        } finally {
            SessionManager.setContext(null);
        }
    }

    @Override
    public void handleMessage(WebSocketSession socket, WebSocketMessage<?> message) throws Exception {
        SessionManager.setContext((String) socket.getAttributes().get("token"));
        try {
            var session = SessionManager.getCurrentSession();
            if (!owns(session, socket) || !session.isActive()) {
                socket.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
            var packet = JSON.parseObject(message.getPayload().toString(), Packet.class);
            if (packet == null || packet.getType() == null) {
                socket.close(CloseStatus.BAD_DATA);
                return;
            }
            if ("ping".equals(packet.getType())) session.getPacketIO().sendPacket("pong", null);
            else session.getController().handlePacket(packet);
        } finally {
            SessionManager.setContext(null);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        SessionManager.setContext((String) socket.getAttributes().get("token"));
        try {
            var session = SessionManager.getCurrentSession();
            // Closing a refused duplicate socket must not close the legitimate one.
            if (owns(session, socket)) session.close();
        } finally {
            SessionManager.setContext(null);
        }
    }
}
