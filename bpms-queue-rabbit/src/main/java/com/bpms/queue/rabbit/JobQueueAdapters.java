package com.bpms.queue.rabbit;

import com.bpms.spi.engine.RuntimeModels.JobRecord;
import com.bpms.spi.port.JobQueuePort;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JobQueueAdapters {

    public static final String QUEUE = "bpms.new.jobs";

    @Bean
    @ConditionalOnProperty(name = "bpms.job-queue", havingValue = "in-process", matchIfMissing = true)
    JobQueuePort inProcessJobQueuePort(ObjectProvider<JobQueuePort.JobHandler> handlers) {
        return job -> handlers.getObject().handle(job);
    }

    @Configuration
    @ConditionalOnProperty(name = "bpms.job-queue", havingValue = "rabbit")
    static class RabbitJobQueueConfiguration {

        @Bean
        Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
            return new Jackson2JsonMessageConverter();
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
        RabbitJobListener rabbitJobListener(ObjectProvider<JobQueuePort.JobHandler> handlers) {
            return new RabbitJobListener(handlers);
        }
    }

    static final class RabbitJobListener {
        private final ObjectProvider<JobQueuePort.JobHandler> handlers;

        RabbitJobListener(ObjectProvider<JobQueuePort.JobHandler> handlers) {
            this.handlers = handlers;
        }

        @RabbitListener(queues = QUEUE)
        void receive(JobRecord job) {
            handlers.getObject().handle(job);
        }
    }
}
