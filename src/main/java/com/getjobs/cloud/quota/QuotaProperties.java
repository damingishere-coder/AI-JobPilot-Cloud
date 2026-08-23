package com.getjobs.cloud.quota;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

/**
 * 免费额度默认值（非敏感），允许环境变量覆盖。
 *
 * <p>首版仅暴露 FREE 计划的 AI 分析与投递确认额度；套餐与付费由后续批次引入。</p>
 */
@Profile({"api", "worker"})
@ConfigurationProperties(prefix = "app.quota")
public class QuotaProperties {

    private Free free = new Free();

    public Free getFree() {
        return free;
    }

    public void setFree(Free free) {
        this.free = free == null ? new Free() : free;
    }

    /** FREE 计划每月 AI 分析次数。 */
    public int analysisLimit() {
        return free.getAnalysis();
    }

    /** FREE 计划每月投递确认次数。 */
    public int deliveryLimit() {
        return free.getDelivery();
    }

    /** FREE 计划额度（每月，UTC 自然月）。 */
    public static class Free {
        private int analysis = 20;
        private int delivery = 10;

        public int getAnalysis() {
            return analysis;
        }

        public void setAnalysis(int analysis) {
            this.analysis = Math.max(0, Math.min(analysis, 1_000_000));
        }

        public int getDelivery() {
            return delivery;
        }

        public void setDelivery(int delivery) {
            this.delivery = Math.max(0, Math.min(delivery, 1_000_000));
        }
    }
}
