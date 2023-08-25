package com.hydev.beaninjectiontest.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class CustomConfig {

    // 10초짜리 Named Bean
    @Primary
    @Bean
    public RestTemplate fooRestTemplate(){
        return new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofSeconds(10))
                .build();
    };

    // 1초짜리 Primary
    @Bean
    public RestTemplate varRestTemplate(){
        return new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofSeconds(1))
                .build();
    }
}
