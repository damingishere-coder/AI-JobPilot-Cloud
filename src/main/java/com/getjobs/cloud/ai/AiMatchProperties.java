package com.getjobs.cloud.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai-match")
public class AiMatchProperties {

    private boolean enabled = false;
    private String provider = "openai";
    private String model = "gpt-4.1-mini";
    private String baseUrl = "https://api.openai.com/v1";
    private String promptVersion = "v1";
    private Duration timeout = new Duration();

    // API key comes from configtree or env, not stored in properties file.
    private String apiKey = "";

    // Redis Stream configuration
    private String streamKey = "";
    private String consumerGroup = "";
    private String consumerName = "";

    // Worker lease and retry configuration
    private int leaseSeconds = 600;
    private int maxAttempts = 3;
    private int retryBaseDelaySeconds = 5;
    private int retryMaxDelaySeconds = 300;

    // Polling delays (parsed by Spring's Duration support in @Scheduled)
    private String streamPollDelay = "2s";
    private String outboxPollDelay = "10s";
    private String dbFallbackDelay = "30s";

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

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public String getStreamKey() {
        return streamKey;
    }

    public void setStreamKey(String streamKey) {
        this.streamKey = streamKey;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public String getConsumerName() {
        return consumerName;
    }

    public void setConsumerName(String consumerName) {
        this.consumerName = consumerName;
    }

    public int getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(int leaseSeconds) {
        this.leaseSeconds = Math.max(30, Math.min(leaseSeconds, 1800));
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = Math.max(1, Math.min(maxAttempts, 10));
    }

    public int getRetryBaseDelaySeconds() {
        return retryBaseDelaySeconds;
    }

    public void setRetryBaseDelaySeconds(int retryBaseDelaySeconds) {
        this.retryBaseDelaySeconds = Math.max(1, Math.min(retryBaseDelaySeconds, 3600));
    }

    public int getRetryMaxDelaySeconds() {
        return retryMaxDelaySeconds;
    }

    public void setRetryMaxDelaySeconds(int retryMaxDelaySeconds) {
        this.retryMaxDelaySeconds = Math.max(1, Math.min(retryMaxDelaySeconds, 3600));
    }

    /**
     * Bounded exponential backoff: base * 2^(attempt-1), capped at max.
     * Shared by match retries and outbox publishing so API and Worker
     * publishers always compute identical delays.
     */
    public int retryDelayForAttempt(int attemptNumber) {
        int shift = Math.min(Math.max(attemptNumber - 1, 0), 6);
        int delay = retryBaseDelaySeconds * (1 << shift);
        return Math.min(delay, retryMaxDelaySeconds);
    }

    public String getStreamPollDelay() {
        return streamPollDelay;
    }

    public void setStreamPollDelay(String streamPollDelay) {
        this.streamPollDelay = streamPollDelay;
    }

    public String getOutboxPollDelay() {
        return outboxPollDelay;
    }

    public void setOutboxPollDelay(String outboxPollDelay) {
        this.outboxPollDelay = outboxPollDelay;
    }

    public String getDbFallbackDelay() {
        return dbFallbackDelay;
    }

    public void setDbFallbackDelay(String dbFallbackDelay) {
        this.dbFallbackDelay = dbFallbackDelay;
    }

    public static class Duration {
        private long connect = 10_000;
        private long request = 90_000;

        public long getConnect() {
            return connect;
        }

        public void setConnect(long connect) {
            this.connect = connect;
        }

        public long getRequest() {
            return request;
        }

        public void setRequest(long request) {
            this.request = request;
        }
    }
}
