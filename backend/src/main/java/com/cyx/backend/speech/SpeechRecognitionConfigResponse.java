package com.cyx.backend.speech;

public record SpeechRecognitionConfigResponse(
        boolean enabled,
        String provider,
        String model,
        String endpoint,
        int sampleRate,
        String format,
        boolean apiKeyConfigured
) {
}
