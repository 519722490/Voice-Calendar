package com.cyx.backend.dto;

public record SpeechRecognitionConfigResponse(
        boolean enabled,
        String provider,
        String model,
        String endpoint,
        int sampleRate,
        String format,
        int maxSentenceSilence,
        boolean semanticPunctuationEnabled,
        boolean apiKeyConfigured
) {
}
