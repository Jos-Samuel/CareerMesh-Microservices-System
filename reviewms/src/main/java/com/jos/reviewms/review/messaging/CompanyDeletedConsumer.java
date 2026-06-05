package com.jos.reviewms.review.messaging;

import com.jos.reviewms.review.ReviewService;
import com.jos.reviewms.review.dto.CompanyMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
public class CompanyDeletedConsumer {
    private final ReviewService reviewService;

    public CompanyDeletedConsumer(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @RabbitListener(queues = "companyDeletedQueue")
    public void consumeMessage(CompanyMessage companyMessage) {
        System.out.println("Received CompanyDeletedEvent for Company ID: " + companyMessage.getId());
        try {
            reviewService.deleteByCompanyId(companyMessage.getId());
            System.out.println("Successfully deleted reviews for Company ID: " + companyMessage.getId());
        } catch (Exception e) {
            System.out.println("Failed to delete reviews for Company ID: " + companyMessage.getId());
            // Review ms could also initiate a rollback if needed, 
            // but usually SAGA orchestrator or primary transaction deals with it.
        }
    }
}
