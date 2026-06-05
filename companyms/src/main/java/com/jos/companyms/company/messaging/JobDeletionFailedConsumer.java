package com.jos.companyms.company.messaging;

import com.jos.companyms.company.Company;
import com.jos.companyms.company.CompanyService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
public class JobDeletionFailedConsumer {
    private final CompanyService companyService;

    public JobDeletionFailedConsumer(CompanyService companyService) {
        this.companyService = companyService;
    }

    @RabbitListener(queues = "jobDeletionFailedQueue")
    public void consumeMessage(Company company) {
        System.out.println("Rollback triggered: Restoring company " + company.getName());
        companyService.createCompany(company); // This will save the company back to the DB
    }
}
