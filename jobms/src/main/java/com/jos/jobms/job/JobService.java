package com.jos.jobms.job;

import com.jos.jobms.job.dto.JobDTO;

import java.util.List;

public interface JobService {
    List<JobDTO>  findAll();
    List<JobDTO> searchJobs(String query);
    void createJob(Job job);

    JobDTO getJobById(Long id);

    boolean deleteJobById(Long id);

    boolean updateJob(Long id, Job updatedJob);
    List<JobDTO> filterJobs(String minSalary, String location, String title);
    void deleteByCompanyId(Long companyId);
}
