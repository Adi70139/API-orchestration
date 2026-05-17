package com.example.flowengine.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.TimeUnit;

@Configuration
public class HttpClientConfig {

    @Value("${http.client.connect-timeout-seconds:10}")
    private int connectTimeoutSeconds;

    @Value("${http.client.read-timeout-seconds:120}")
    private int readTimeoutSeconds;

    @Value("${http.client.write-timeout-seconds:30}")
    private int writeTimeoutSeconds;

    @Value("${http.client.max-connections:20}")
    private int maxConnections;

    @Value("${http.client.max-connections-per-host:10}")
    private int maxConnectionsPerHost;

    @Value("${http.client.keep-alive-minutes:5}")
    private int keepAliveMinutes;

    @Value("${http.client.max-concurrent-requests:20}")
    private int maxConcurrentRequests;

    @Value("${http.client.max-concurrent-requests-per-host:10}")
    private int maxConcurrentRequestsPerHost;

    @Value("${scheduler.thread-pool-size:20}")
    private int schedulerThreadPoolSize;

    @Bean
    public OkHttpClient okHttpClient() {
        // Connection pool — raise limits for parallel module runs
        ConnectionPool connectionPool = new ConnectionPool(
                maxConnections,
                keepAliveMinutes,
                TimeUnit.MINUTES
        );

        // Dispatcher controls concurrent async calls
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(maxConcurrentRequests);
        dispatcher.setMaxRequestsPerHost(maxConcurrentRequestsPerHost);

        return new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
                .connectionPool(connectionPool)
                .dispatcher(dispatcher)
                .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(schedulerThreadPoolSize);
        scheduler.setThreadNamePrefix("flow-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }
}