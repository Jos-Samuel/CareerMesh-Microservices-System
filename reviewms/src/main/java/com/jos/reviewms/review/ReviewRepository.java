package com.jos.reviewms.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    List<Review> findByCompanyId(Long companyId);
    List<Review> findByCompanyIdIn(List<Long> companyIds);
    void deleteByCompanyId(Long companyId);
}
