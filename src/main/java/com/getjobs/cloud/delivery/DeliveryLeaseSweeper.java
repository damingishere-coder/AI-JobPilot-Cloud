package com.getjobs.cloud.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled recovery of expired delivery leases: tasks whose plugin execution
 * lease ran out return to CONFIRMED (or FAILED after max attempts). Runs in
 * the API profile and relies on the SECURITY DEFINER sweep function.
 */
@Component
@Profile("api")
public class DeliveryLeaseSweeper {
    private static final Logger log = LoggerFactory.getLogger(DeliveryLeaseSweeper.class);

    private final DeliveryService delivery;

    public DeliveryLeaseSweeper(DeliveryService delivery) {
        this.delivery = delivery;
    }

    @Scheduled(fixedDelayString = "${app.delivery.lease-sweep-delay:60s}")
    public void recoverExpiredLeases() {
        try {
            int recovered = delivery.recoverExpiredLeases();
            if (recovered > 0) {
                log.info("已恢复 {} 条到期投递租约", recovered);
            }
        } catch (RuntimeException exception) {
            log.warn("投递租约恢复扫描失败，类型={}", exception.getClass().getSimpleName());
        }
    }
}
