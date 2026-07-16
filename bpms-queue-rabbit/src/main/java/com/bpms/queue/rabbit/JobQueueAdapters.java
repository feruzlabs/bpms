package com.bpms.queue.rabbit;

import com.bpms.spi.engine.RuntimeModels.JobRecord;
import com.bpms.spi.port.JobQueuePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JobQueueAdapters {

    public static final String QUEUE = "bpms.new.jobs";

    @Bean
    @ConditionalOnProperty(name = "bpms.job-queue", havingValue = "in-process", matchIfMissing = true)
    JobQueuePort inProcessJobQueuePort(JobQueuePort.JobHandler handler) {
        // JobDispatcher is @Primary — routes PROCESS_START / SERVICE_TASK
        return job -> handler.handle(job);
    }

    @Configuration
    @EnableRabbit   // @RabbitListener tinglovchisini yoqadi (aks holda consumer ro'yxatga olinmaydi -> 0 consumer)
    @ConditionalOnProperty(name = "bpms.job-queue", havingValue = "rabbit")
    static class RabbitJobQueueConfiguration {

        /**
         * Uses the application's ObjectMapper (has JavaTimeModule → Instant runAt works) and trusts our
         * packages so JobRecord can be deserialized on the consumer side. Without this, Instant serialization
         * fails on publish and/or JobRecord deserialization is blocked on consume → jobs never run (async "broken").
         */
        @Bean
        Jackson2JsonMessageConverter jackson2JsonMessageConverter(ObjectMapper objectMapper) {
            Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
            DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
            typeMapper.setTrustedPackages("*");
            converter.setJavaTypeMapper(typeMapper);
            return converter;
        }

        @Bean
        Queue bpmsNewJobsQueue() {
            return new Queue(QUEUE, true);
        }

        @Bean
        JobQueuePort rabbitJobQueuePort(RabbitTemplate template) {
            return job -> template.convertAndSend(QUEUE, job);
        }

        @Bean
        RabbitJobListener rabbitJobListener(JobQueuePort.JobHandler handler) {
            return new RabbitJobListener(handler);
        }
    }

    static final class RabbitJobListener {
        private final JobQueuePort.JobHandler handler;

        RabbitJobListener(JobQueuePort.JobHandler handler) {
            this.handler = handler;
        }

        @RabbitListener(queues = QUEUE)
        void receive(JobRecord job) {
            handler.handle(job);
        }
    }
}
