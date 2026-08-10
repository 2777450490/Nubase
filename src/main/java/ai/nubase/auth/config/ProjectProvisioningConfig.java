package ai.nubase.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

@Configuration
public class ProjectProvisioningConfig {

    @Bean(name = "projectProvisioningExecutor")
    public Executor projectProvisioningExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("nubase-project-provisioning-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }

    @Bean(name = "projectProvisioningLeaseHeartbeatExecutor", destroyMethod = "shutdown")
    public ScheduledExecutorService projectProvisioningLeaseHeartbeatExecutor() {
        CustomizableThreadFactory threadFactory =
                new CustomizableThreadFactory("nubase-project-provisioning-lease-");
        threadFactory.setDaemon(true);
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(4, threadFactory);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
