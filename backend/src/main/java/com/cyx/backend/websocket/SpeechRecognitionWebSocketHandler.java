package com.cyx.backend.websocket;

import com.cyx.backend.config.SpeechRecognitionProperties;
import com.cyx.backend.service.SpeechRecognitionSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

@Component
public class SpeechRecognitionWebSocketHandler extends AbstractWebSocketHandler {
    private static final String SESSION_ATTRIBUTE = "speechRecognitionSession";

    private final SpeechRecognitionProperties properties;
    private final ObjectMapper objectMapper;

    public SpeechRecognitionWebSocketHandler(
            SpeechRecognitionProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        SpeechRecognitionSession speechSession = new SpeechRecognitionSession(session, properties, objectMapper);
        session.getAttributes().put(SESSION_ATTRIBUTE, speechSession);
        speechSession.sendStatus("connected", "语音通道已连接");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        SpeechRecognitionSession speechSession = getSpeechSession(session);
        JsonNode command = objectMapper.readTree(message.getPayload());
        String type = command.path("type").asText("");

        if ("start".equals(type)) {
            speechSession.start();
            return;
        }

        if ("stop".equals(type)) {
            speechSession.finish();
            return;
        }

        speechSession.sendError("未知的语音指令：" + type);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        SpeechRecognitionSession speechSession = getSpeechSession(session);
        ByteBuffer payload = message.getPayload();
        byte[] audioBytes = new byte[payload.remaining()];
        payload.get(audioBytes);
        speechSession.sendAudio(audioBytes);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        SpeechRecognitionSession speechSession = getSpeechSession(session);
        speechSession.sendError("语音连接异常：" + exception.getMessage());
        speechSession.close();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SpeechRecognitionSession speechSession = getSpeechSession(session);
        speechSession.close();
    }

    private SpeechRecognitionSession getSpeechSession(WebSocketSession session) {
        Object attribute = session.getAttributes().get(SESSION_ATTRIBUTE);
        if (attribute instanceof SpeechRecognitionSession speechSession) {
            return speechSession;
        }

        SpeechRecognitionSession speechSession = new SpeechRecognitionSession(session, properties, objectMapper);
        session.getAttributes().put(SESSION_ATTRIBUTE, speechSession);
        return speechSession;
    }
}
