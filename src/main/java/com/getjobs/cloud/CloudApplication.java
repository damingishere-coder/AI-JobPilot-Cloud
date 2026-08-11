package com.getjobs.cloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 云端进程入口。
 *
 * <p>该入口只扫描 {@code com.getjobs.cloud}，因此不会加载仍在迁移中的本地版
 * SQLite、Cookie 和 Playwright 组件。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CloudApplication {
    public static void main(String[] args) {
        SpringApplication.run(CloudApplication.class, args);
    }
}
