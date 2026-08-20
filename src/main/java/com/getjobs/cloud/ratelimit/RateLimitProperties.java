package com.getjobs.cloud.ratelimit;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 保护性限流参数（AI 匹配与简历上传等已登录用户接口）。
 * 阈值与窗口均可通过环境变量覆盖；登录/注册限流仍由
 * {@link com.getjobs.cloud.auth.AuthProperties} 管理。
 */
@Validated
@Profile("api")
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {
    @Positive
    private int matchAnalyzeLimit = 30;
    @NotNull
    private Duration matchAnalyzeWindow = Duration.ofMinutes(1);
    @Positive
    private int matchBatchLimit = 10;
    @NotNull
    private Duration matchBatchWindow = Duration.ofMinutes(1);
    @Positive
    private int resumeUploadLimit = 10;
    @NotNull
    private Duration resumeUploadWindow = Duration.ofMinutes(1);

    /**
     * 插件待办任务轮询（GET /api/plugin/tasks/pending），按设备（deviceId）
     * 维度计数。默认 60 次/分钟，约为 poll-after-seconds=10 正常轮询节奏
     * （6 次/分钟）的 10 倍余量，不限制正常设备轮询。
     */
    @Positive
    private int pluginTaskPollLimit = 60;
    @NotNull
    private Duration pluginTaskPollWindow = Duration.ofMinutes(1);

    /**
     * 岗位采集上传（POST /api/plugin/jobs/capture 与 /batch-capture），
     * 已实现并按认证后的 deviceId 维度限流；绝不使用原始 Token，
     * 采集上传成功后写审计事件。
     */
    @Positive
    private int pluginJobCaptureLimit = 30;
    @NotNull
    private Duration pluginJobCaptureWindow = Duration.ofMinutes(1);

    public int getMatchAnalyzeLimit() {
        return matchAnalyzeLimit;
    }

    public void setMatchAnalyzeLimit(int matchAnalyzeLimit) {
        this.matchAnalyzeLimit = matchAnalyzeLimit;
    }

    public Duration getMatchAnalyzeWindow() {
        return matchAnalyzeWindow;
    }

    public void setMatchAnalyzeWindow(Duration matchAnalyzeWindow) {
        this.matchAnalyzeWindow = matchAnalyzeWindow;
    }

    public int getMatchBatchLimit() {
        return matchBatchLimit;
    }

    public void setMatchBatchLimit(int matchBatchLimit) {
        this.matchBatchLimit = matchBatchLimit;
    }

    public Duration getMatchBatchWindow() {
        return matchBatchWindow;
    }

    public void setMatchBatchWindow(Duration matchBatchWindow) {
        this.matchBatchWindow = matchBatchWindow;
    }

    public int getResumeUploadLimit() {
        return resumeUploadLimit;
    }

    public void setResumeUploadLimit(int resumeUploadLimit) {
        this.resumeUploadLimit = resumeUploadLimit;
    }

    public Duration getResumeUploadWindow() {
        return resumeUploadWindow;
    }

    public void setResumeUploadWindow(Duration resumeUploadWindow) {
        this.resumeUploadWindow = resumeUploadWindow;
    }

    public int getPluginTaskPollLimit() {
        return pluginTaskPollLimit;
    }

    public void setPluginTaskPollLimit(int pluginTaskPollLimit) {
        this.pluginTaskPollLimit = pluginTaskPollLimit;
    }

    public Duration getPluginTaskPollWindow() {
        return pluginTaskPollWindow;
    }

    public void setPluginTaskPollWindow(Duration pluginTaskPollWindow) {
        this.pluginTaskPollWindow = pluginTaskPollWindow;
    }

    public int getPluginJobCaptureLimit() {
        return pluginJobCaptureLimit;
    }

    public void setPluginJobCaptureLimit(int pluginJobCaptureLimit) {
        this.pluginJobCaptureLimit = pluginJobCaptureLimit;
    }

    public Duration getPluginJobCaptureWindow() {
        return pluginJobCaptureWindow;
    }

    public void setPluginJobCaptureWindow(Duration pluginJobCaptureWindow) {
        this.pluginJobCaptureWindow = pluginJobCaptureWindow;
    }
}
