package com.jos.companyms.company.impl;

import com.jos.companyms.company.Company;
import com.jos.companyms.company.CompanyRepository;
import com.jos.companyms.company.CompanyService;
import com.jos.companyms.company.clients.ReviewClient;
import com.jos.companyms.company.dto.ReviewMessage;
import com.jos.companyms.company.messaging.CompanyMessageProducer;
import jakarta.ws.rs.NotFoundException;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
public class CompanyServiceImpl implements CompanyService {
    private CompanyRepository companyRepository;
    private ReviewClient reviewClient;
    private CompanyMessageProducer companyMessageProducer;

    public CompanyServiceImpl(CompanyRepository companyRepository, ReviewClient reviewClient, CompanyMessageProducer companyMessageProducer) {
        this.reviewClient = reviewClient;
        this.companyRepository = companyRepository;
        this.companyMessageProducer = companyMessageProducer;
    }

    @Override
    public List<Company> getAllCompanies() {
        return companyRepository.findAll();
    }

    @Override
    @CacheEvict(value = "company", key = "#id")
    public boolean updateCompany(Company company, Long id) {
        Optional<Company> companyOptional = companyRepository.findById(id);
        if(companyOptional.isPresent()) {
            Company companyToUpdate = companyOptional.get();
            companyToUpdate.setName(company.getName());
            companyToUpdate.setDescription(company.getDescription());
            companyRepository.save(companyToUpdate);
            return true;
        }
        else return false;
    }

    @Override
    public void createCompany(Company company) {
        companyRepository.save(company);
    }

    @Override
    @CacheEvict(value = "company", key = "#id")
    public boolean deleteCompanyById(Long id) {
        Optional<Company> companyOptional = companyRepository.findById(id);
        if(companyOptional.isPresent()) {
            Company company = companyOptional.get();
            // Publish message before deleting
            companyMessageProducer.sendMessage(company);
            
            companyRepository.deleteById(id);
            return true;
        }
        else{
            return false;
        }
    }

    @Override
    @Cacheable(value = "company", key = "#id", sync = true)
    public Company getCompanyById(Long id) {
        return companyRepository.findById(id).orElse(null);
    }

    @Override
    @CacheEvict(value = "company", key = "#reviewMessage.companyId")
    public void updateCompanyRating(ReviewMessage reviewMessage) {
        System.out.println(reviewMessage.getDescription());
        Company company = companyRepository.findById(reviewMessage.getCompanyId()).orElseThrow(()-> new NotFoundException("Company not found with id: " + reviewMessage.getCompanyId()));
        double averageRating = reviewClient.getAverageRating(reviewMessage.getCompanyId());
        company.setRating(averageRating);
        companyRepository.save(company);
    }

    @Override
    public List<Company> getCompaniesByIds(List<Long> ids) {
        return companyRepository.findAllById(ids);
    }
}
