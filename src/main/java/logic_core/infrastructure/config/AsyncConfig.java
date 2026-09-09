package logic_core.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Asynchronous email delivery (Issue #20).
 *
 * <p>Bounded thread pool with daemon threads: email dispatch is a
 * best-effort background operation and must never block the request thread
 * or keep the JVM alive. {@link java.util.concurrent.CompletableFuture}
 * supplies the runtime dispatch; this executor is exposed as the named
 * Spring bean for integration/observability and future use.
 */
@Configuration
@EnableAsync
public class AsyncConfig
{
    @Bean(name = "emailTaskExecutor")
    public Executor emailTaskExecutor()
    {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("email-delivery-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setDaemon(true);
        executor.initialize();
        return executor;
    }
}