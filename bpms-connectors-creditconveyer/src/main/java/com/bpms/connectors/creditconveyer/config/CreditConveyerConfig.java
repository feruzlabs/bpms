package com.bpms.connectors.creditconveyer.config;

import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class CreditConveyerConfig {

    @Bean
    Gson creditConveyerGson() {
        return new Gson();
    }

    @Bean
    OkHttpClient creditConveyerHttpClient(
            @Value("${creditconveyer.http.connect-timeout:10s}") Duration connectTimeout,
            @Value("${creditconveyer.http.read-timeout:60s}") Duration readTimeout,
            @Value("${creditconveyer.http.write-timeout:30s}") Duration writeTimeout
    ) {
        return new OkHttpClient.Builder()
                .connectTimeout(connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(readTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .writeTimeout(writeTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }
}
