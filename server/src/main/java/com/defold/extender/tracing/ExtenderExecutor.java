package com.defold.extender.tracing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextScheduledExecutorService;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionHandler;

@Configuration(proxyBeanMethods = false)
class ExtenderExecutor {

    @Value("${extender.tasks.executor.pool-size:35}")
    private int executorPoolSize;

    @Value("${spring.threads.virtual.enabled:false}")
    private boolean isVirtualThreadsEnabled;

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    static class AsyncConfig implements AsyncConfigurer, WebMvcConfigurer {

        @Value("${spring.threads.virtual.enabled:false}")
        private boolean isVirtualThreadsEnabled;
    
        @Override
        public Executor getAsyncExecutor() {
            ThreadFactory factory = isVirtualThreadsEnabled ? Thread.ofVirtual().factory() : Executors.defaultThreadFactory();
            return ContextExecutorService.wrap(Executors.newCachedThreadPool(factory), ContextSnapshot::captureAll);
        }

        @Override
        public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
            SimpleAsyncTaskExecutor exec = new SimpleAsyncTaskExecutor(r -> new Thread(ContextSnapshotFactory.builder().build().captureAll().wrap(r)));
            configurer.setTaskExecutor(exec);
        }
    }


    /**
     * NAME OF THE BEAN IS IMPORTANT!
     * <p>
     * We need to wrap this for @Async related things to propagate the context.
     *
     * @see EnableAsync
     */
    // [Observability] instrumenting executors
    @Bean(name = "extenderTaskExecutor", destroyMethod = "shutdown")
    ThreadPoolTaskScheduler threadPoolTaskScheduler() {
        ThreadPoolTaskScheduler threadPoolTaskScheduler = new ThreadPoolTaskScheduler() {
            @Override
            protected ExecutorService initializeExecutor(ThreadFactory threadFactory, RejectedExecutionHandler rejectedExecutionHandler) {
                ExecutorService executorService = super.initializeExecutor(threadFactory, rejectedExecutionHandler);
                return ContextExecutorService.wrap(executorService, ContextSnapshot::captureAll);
            }


                @Override
                public ScheduledExecutorService getScheduledExecutor() throws IllegalStateException {
                    return ContextScheduledExecutorService.wrap(super.getScheduledExecutor());
                }
        };
        threadPoolTaskScheduler.setPoolSize(executorPoolSize);
        if (isVirtualThreadsEnabled) {
            threadPoolTaskScheduler.setThreadFactory(Thread.ofVirtual().factory());
        }
        threadPoolTaskScheduler.initialize();
        return threadPoolTaskScheduler;
    }
}