package com.cyx.backend.speech;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/speech")
public class SpeechRecognitionController {
    private final SpeechRecognitionProperties properties;

    public SpeechRecognitionController(SpeechRecognitionProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/config")
    public SpeechRecognitionConfigResponse getConfig() {
        return new SpeechRecognitionConfigResponse(
                properties.isEnabled(),
                properties.getProvider(),
                properties.getModel(),
                properties.getEndpoint(),
                properties.getSampleRate(),
                properties.getFormat(),
                properties.isApiKeyConfigured()
        );
    }
}
