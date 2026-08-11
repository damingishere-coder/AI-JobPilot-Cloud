package com.getjobs.cloud.process;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("worker")
public class WorkerBootstrap {
    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        log.info("Cloud AI Worker 基础进程已就绪；业务队列消费将在第 5 轮启用");
    }
}
