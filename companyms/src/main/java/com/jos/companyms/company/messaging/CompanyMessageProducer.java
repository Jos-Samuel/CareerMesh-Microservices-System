package com.jos.companyms.company.messaging;

import com.jos.companyms.company.Company;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class CompanyMessageProducer {
    private final RabbitTemplate rabbitTemplate;

    public CompanyMessageProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void sendMessage(Company company) {
        rabbitTemplate.convertAndSend("companyDeletedQueue", company);
    }
}
