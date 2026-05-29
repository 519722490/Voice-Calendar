package com.cyx.backend.service;

import com.cyx.backend.config.SpeechRecognitionProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

public class SpeechRecognitionSession {
    private final WebSocketSession frontendSession;
    private final SpeechRecognitionProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String taskId = UUID.randomUUID().toString();
    private final Object frontendSendLock = new Object();
    private final Object dashScopeSendLock = new Object();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean taskStarted = new AtomicBoolean(false);
    private final AtomicBoolean finishSent = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final StringBuilder dashScopeTextBuffer = new StringBuilder();

    private volatile WebSocket dashScopeSocket;
    private CompletableFuture<Void> dashScopeSendChain = CompletableFuture.completedFuture(null);

    public SpeechRecognitionSession(
            WebSocketSession frontendSession,
            SpeechRecognitionProperties properties,
            ObjectMapper objectMapper
    ) {
        this.frontendSession = frontendSession;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public void start() {
        if (!properties.isEnabled()) {
            sendError("语音识别未启用，请先配置 voice-calendar.speech.enabled=true");
            return;
        }

        if (!properties.isApiKeyConfigured()) {
            sendError("语音识别 API Key 未配置，请先填写 voice-calendar.speech.api-key");
            return;
        }

        if (!started.compareAndSet(false, true)) {
            sendStatus("starting", "语音识别正在进行中");
            return;
        }

        sendStatus("starting", "正在连接语音识别服务");

        httpClient.newWebSocketBuilder()
                .header("Authorization", "Bearer " + properties.getApiKey())
                .buildAsync(URI.create(properties.getEndpoint()), new DashScopeListener())
                .whenComplete((socket, exception) -> {
                    if (exception != null) {
                        sendError("连接语音识别服务失败：" + exception.getMessage());
                        return;
                    }
                    dashScopeSocket = socket;
                });
    }

    public void sendAudio(byte[] audioBytes) {
        WebSocket socket = dashScopeSocket;
        if (socket == null || !taskStarted.get() || closed.get() || audioBytes.length == 0) {
            return;
        }

        enqueueDashScopeSend(socket, webSocket -> webSocket.sendBinary(ByteBuffer.wrap(audioBytes), true));
    }

    public void finish() {
        WebSocket socket = dashScopeSocket;
        if (socket == null || !finishSent.compareAndSet(false, true)) {
            sendStatus("stopped", "语音识别已停止");
            return;
        }

        sendStatus("stopping", "正在结束语音识别");
        enqueueDashScopeSend(socket, webSocket -> webSocket.sendText(buildFinishTaskMessage(), true));
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        WebSocket socket = dashScopeSocket;
        if (socket != null && !finishSent.get()) {
            try {
                finish();
            } catch (Exception ignored) {
                // Ignore cleanup failures because the frontend session is already closing.
            }
        }

        if (socket != null) {
            socket.abort();
        }
    }

    public void sendStatus(String type, String message) {
        sendToFrontend(Map.of(
                "type", type,
                "message", message
        ));
    }

    public void sendError(String message) {
        sendToFrontend(Map.of(
                "type", "error",
                "message", message
        ));
    }

    private void handleDashScopeMessage(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode header = root.path("header");
            String event = header.path("event").asText("");

            if ("task-started".equals(event)) {
                taskStarted.set(true);
                sendStatus("ready", "可以开始说话");
                return;
            }

            if ("result-generated".equals(event)) {
                sendTranscript(root);
                return;
            }

            if ("task-finished".equals(event)) {
                sendStatus("finished", "语音识别已完成");
                close();
                return;
            }

            if ("task-failed".equals(event)) {
                String errorMessage = header.path("error_message").asText("语音识别失败");
                sendError(errorMessage);
                close();
            }
        } catch (Exception exception) {
            sendError("解析语音识别结果失败：" + exception.getMessage());
        }
    }

    private void sendTranscript(JsonNode root) {
        JsonNode sentence = root.path("payload").path("output").path("sentence");
        String text = sentence.path("text").asText("");
        boolean sentenceEnd = sentence.path("sentence_end").asBoolean(false);

        if (text.isBlank()) {
            text = root.path("payload").path("output").path("text").asText("");
        }

        if (text.isBlank()) {
            return;
        }

        sendToFrontend(Map.of(
                "type", "transcript",
                "text", text,
                "final", sentenceEnd
        ));
    }

    private String buildRunTaskMessage() {
        return writeJson(Map.of(
                "header", Map.of(
                        "action", "run-task",
                        "task_id", taskId,
                        "streaming", "duplex"
                ),
                "payload", Map.of(
                        "task_group", "audio",
                        "task", "asr",
                        "function", "recognition",
                        "model", properties.getModel(),
                        "parameters", Map.of(
                                "format", properties.getFormat(),
                                "sample_rate", properties.getSampleRate()
                        ),
                        "input", Map.of()
                )
        ));
    }

    private String buildFinishTaskMessage() {
        return writeJson(Map.of(
                "header", Map.of(
                        "action", "finish-task",
                        "task_id", taskId,
                        "streaming", "duplex"
                ),
                "payload", Map.of(
                        "input", Map.of()
                )
        ));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException("JSON 序列化失败", exception);
        }
    }

    private void sendToFrontend(Object payload) {
        if (!frontendSession.isOpen()) {
            return;
        }

        synchronized (frontendSendLock) {
            try {
                WebSocketMessage<?> message = new TextMessage(writeJson(payload));
                frontendSession.sendMessage(message);
            } catch (IOException exception) {
                throw new IllegalStateException("发送语音消息到前端失败", exception);
            }
        }
    }

    private void enqueueDashScopeSend(
            WebSocket socket,
            DashScopeSendOperation operation
    ) {
        synchronized (dashScopeSendLock) {
            dashScopeSendChain = dashScopeSendChain
                    .exceptionally(exception -> null)
                    .thenCompose(ignored -> operation.send(socket).thenApply(webSocket -> (Void) null))
                    .exceptionally(exception -> {
                        sendError("发送音频到语音识别服务失败：" + exception.getMessage());
                        return (Void) null;
                    });
        }
    }

    @FunctionalInterface
    private interface DashScopeSendOperation {
        CompletableFuture<WebSocket> send(WebSocket socket);
    }

    private class DashScopeListener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            dashScopeSocket = webSocket;
            enqueueDashScopeSend(webSocket, socket -> socket.sendText(buildRunTaskMessage(), true));
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            dashScopeTextBuffer.append(data);

            if (last) {
                String message = dashScopeTextBuffer.toString();
                dashScopeTextBuffer.setLength(0);
                handleDashScopeMessage(message);
            }

            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            sendStatus("closed", "语音识别连接已关闭");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            sendError("语音识别服务异常：" + error.getMessage());
        }
    }
}
