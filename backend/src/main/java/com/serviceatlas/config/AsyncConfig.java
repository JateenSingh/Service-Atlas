package com.serviceatlas.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Thread pool for background scans (FR-7.1). */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("scanExecutor")
    public Executor scanExecutor(ServiceAtlasProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(Math.max(2, properties.getScan().getParallelism()));
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("scan-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
