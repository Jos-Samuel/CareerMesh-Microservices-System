package com.jos.jobms.job.messaging;

import com.jos.jobms.job.JobService;
import com.jos.jobms.job.dto.CompanyMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class CompanyDeletedConsumer {
    private final JobService jobService;
    private final RabbitTemplate rabbitTemplate;

    public CompanyDeletedConsumer(JobService jobService, RabbitTemplate rabbitTemplate) {
        this.jobService = jobService;
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(queues = "companyDeletedQueue")
    public void consumeMessage(CompanyMessage companyMessage) {
        System.out.println("Received CompanyDeletedEvent for Company ID: " + companyMessage.getId());
        try {
            jobService.deleteByCompanyId(companyMessage.getId());
            System.out.println("Successfully deleted jobs for Company ID: " + companyMessage.getId());
        } catch (Exception e) {
            System.out.println("Error deleting jobs. Initiating SAGA Rollback for Company ID: " + companyMessage.getId());
            rabbitTemplate.convertAndSend("jobDeletionFailedQueue", companyMessage);
        }
    }
}
