package com.cyx.backend.config;

import com.cyx.backend.websocket.SpeechRecognitionWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class SpeechRecognitionWebSocketConfig implements WebSocketConfigurer {
    private final SpeechRecognitionWebSocketHandler speechRecognitionWebSocketHandler;

    public SpeechRecognitionWebSocketConfig(SpeechRecognitionWebSocketHandler speechRecognitionWebSocketHandler) {
        this.speechRecognitionWebSocketHandler = speechRecognitionWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(speechRecognitionWebSocketHandler, "/ws/speech")
                .setAllowedOrigins("http://localhost:5173", "http://127.0.0.1:5173");
    }
}
