package com.example.transfer.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "transfers";
    public static final String QUEUE = "transfer.processing";
    public static final String ROUTING_KEY = "transfer.accepted";

    @Bean
    DirectExchange transferExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    Queue transferQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    Binding transferBinding(Queue transferQueue, DirectExchange transferExchange) {
        return BindingBuilder.bind(transferQueue)
                .to(transferExchange)
                .with(ROUTING_KEY);
    }
}
