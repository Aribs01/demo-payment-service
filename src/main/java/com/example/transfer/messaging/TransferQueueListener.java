package com.example.transfer.messaging;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.example.transfer.transfer.service.TransferWorkflow;

@Component
public class TransferQueueListener {

    private static final Logger log = LoggerFactory.getLogger(TransferQueueListener.class);

    private final TransferWorkflow workflow;

    public TransferQueueListener(TransferWorkflow workflow) {
        this.workflow = workflow;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE)
    public void onTransfer(String transferId) {
        log.info("Received transfer {} from RabbitMQ", transferId);
        workflow.advance(UUID.fromString(transferId));
    }
}
