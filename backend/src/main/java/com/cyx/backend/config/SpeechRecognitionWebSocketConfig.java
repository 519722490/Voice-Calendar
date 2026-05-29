package com.cyx.backend.config;

import com.cyx.backend.security.SpeechWebSocketAuthInterceptor;
import com.cyx.backend.websocket.SpeechRecognitionWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class SpeechRecognitionWebSocketConfig implements WebSocketConfigurer {
    private final SpeechRecognitionWebSocketHandler speechRecognitionWebSocketHandler;
    private final SpeechWebSocketAuthInterceptor speechWebSocketAuthInterceptor;

    public SpeechRecognitionWebSocketConfig(
            SpeechRecognitionWebSocketHandler speechRecognitionWebSocketHandler,
            SpeechWebSocketAuthInterceptor speechWebSocketAuthInterceptor
    ) {
        this.speechRecognitionWebSocketHandler = speechRecognitionWebSocketHandler;
        this.speechWebSocketAuthInterceptor = speechWebSocketAuthInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(speechRecognitionWebSocketHandler, "/ws/speech")
                .addInterceptors(speechWebSocketAuthInterceptor)
                .setAllowedOrigins("http://localhost:5173", "http://127.0.0.1:5173");
    }
}
