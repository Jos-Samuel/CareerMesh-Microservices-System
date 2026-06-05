package com.jos.jobms.job.impl;


import com.jos.jobms.job.Job;
import com.jos.jobms.job.JobRepository;
import com.jos.jobms.job.JobService;
import com.jos.jobms.job.clients.CompanyClient;
import com.jos.jobms.job.clients.ReviewClient;
import com.jos.jobms.job.dto.JobDTO;
import com.jos.jobms.job.external.Company;
import com.jos.jobms.job.external.Review;
import com.jos.jobms.job.mapper.JobMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.jos.jobms.job.JobSpecification;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class JobServiceImpl implements JobService {
    // private List<Job> jobs = new ArrayList<>();
    JobRepository jobRepository;

    @Autowired
    RestTemplate restTemplate;

    private CompanyClient companyClient;
    private ReviewClient reviewClient;
    int attempt =0;

    public JobServiceImpl(JobRepository jobRepository, CompanyClient companyClient, ReviewClient reviewClient) {
        this.jobRepository = jobRepository;
        this.companyClient = companyClient;
        this.reviewClient = reviewClient;
    }

    @Override
//    @CircuitBreaker(name = "companyBreaker", fallbackMethod = "companyBreakerFallback")
    @Retry(name = "companyBreaker", fallbackMethod = "companyBreakerFallback")
//    @RateLimiter(name = "companyBreaker", fallbackMethod = "companyBreakerFallback")
    public List<JobDTO> findAll() {
        System.out.println("Attempt: " + (++attempt));
        List<Job> jobs = jobRepository.findAll();
        List<JobDTO> jobDTOS = new ArrayList<>();

        return jobs.stream().map(this::convertToDto).collect(Collectors.toList());
    }

    public List<String> companyBreakerFallback(Exception e) {
        List<String> list = new ArrayList<>();
        list.add("Sorry For the Incovienience, Company Service is down at the moment. Please try again later.");
        return list;
    }

    @Override
    @Retry(name = "companyBreaker", fallbackMethod = "companyBreakerFallback")
    public List<JobDTO> searchJobs(String query) {
        List<Job> jobs = jobRepository.findByTitleContainingIgnoreCaseOrLocationContainingIgnoreCase(query, query);
        return jobs.stream().map(this::convertToDto).collect(Collectors.toList());
    }

    private JobDTO convertToDto(Job job){
            Company company = companyClient.getCompany(job.getCompanyId());

            List<Review> reviews = reviewClient.getReviews(job.getCompanyId());
            JobDTO jobDTO = JobMapper.mapToJobWithCompanyDTO(job, company, reviews);
            return jobDTO;
    }

    @Override
    public void createJob(Job job) {
        jobRepository.save(job);
    }

    @Override
    @Cacheable(value = "job", key = "#id", sync = true)
    public JobDTO getJobById(Long id) {
        Job job = jobRepository.findById(id).orElse(null);
        return convertToDto(job);
    }

    @Override
    @CacheEvict(value = "job", key = "#id")
    public boolean deleteJobById(Long id) {
        try{
            jobRepository.deleteById(id);
            return true;
        }
        catch(Exception e){
            return false;
        }
    }

    @Override
    @CacheEvict(value = "job", key = "#id")
    public boolean updateJob(Long id, Job updatedJob) {
        Optional<Job> jobOptional = jobRepository.findById(id);
        if(jobOptional.isPresent()){
            Job job = jobOptional.get();
            job.setTitle(updatedJob.getTitle());
            job.setDescription(updatedJob.getDescription());
            job.setMinSalary(updatedJob.getMinSalary());
            job.setMaxSalary(updatedJob.getMaxSalary());
            job.setLocation(updatedJob.getLocation());
            jobRepository.save(job);
            return true;
        }
        return false;
    }

    @Override
    @Retry(name = "companyBreaker", fallbackMethod = "companyBreakerFallback")
    public List<JobDTO> filterJobs(String minSalary, String location, String title) {
        List<Job> jobs = jobRepository.findAll(JobSpecification.filterJobs(minSalary, location, title));
        
        if (jobs.isEmpty()) {
            return new ArrayList<>();
        }

        // Batch Fetching!
        List<Long> companyIds = jobs.stream()
                .map(Job::getCompanyId)
                .distinct()
                .collect(Collectors.toList());

        List<Company> companies = companyClient.getCompaniesByIds(companyIds);
        List<Review> reviews = reviewClient.getReviewsByCompanyIds(companyIds);

        // Map for quick lookup
        java.util.Map<Long, Company> companyMap = companies.stream()
                .collect(Collectors.toMap(Company::getId, c -> c, (a, b) -> a));
        
        java.util.Map<Long, List<Review>> reviewMap = reviews.stream()
                .collect(Collectors.groupingBy(Review::getCompanyId));

        return jobs.stream().map(job -> {
            Company company = companyMap.get(job.getCompanyId());
            List<Review> companyReviews = reviewMap.getOrDefault(job.getCompanyId(), new ArrayList<>());
            return JobMapper.mapToJobWithCompanyDTO(job, company, companyReviews);
        }).collect(Collectors.toList());
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void deleteByCompanyId(Long companyId) {
        jobRepository.deleteByCompanyId(companyId);
    }
}
