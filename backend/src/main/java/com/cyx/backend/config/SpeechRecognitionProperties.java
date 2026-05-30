package com.cyx.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "voice-calendar.speech")
public class SpeechRecognitionProperties {
    private boolean enabled;
    private String provider = "dashscope";
    private String apiKey = "";
    private String model = "fun-asr-realtime";
    private String endpoint = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
    private int sampleRate = 16000;
    private String format = "pcm";
    private int maxSentenceSilence = 1300;
    private boolean semanticPunctuationEnabled;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public int getMaxSentenceSilence() {
        return maxSentenceSilence;
    }

    public void setMaxSentenceSilence(int maxSentenceSilence) {
        this.maxSentenceSilence = maxSentenceSilence;
    }

    public boolean isSemanticPunctuationEnabled() {
        return semanticPunctuationEnabled;
    }

    public void setSemanticPunctuationEnabled(boolean semanticPunctuationEnabled) {
        this.semanticPunctuationEnabled = semanticPunctuationEnabled;
    }

    public boolean isApiKeyConfigured() {
        return StringUtils.hasText(apiKey);
    }
}
