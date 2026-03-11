package com.embarkx.firstjobapp.review;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("companies/{companyId}")
public class ReviewController {
    private ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/reviews")
    public ResponseEntity<List<Review>> getAllReviews(@PathVariable Long companyId) {
        List<Review> reviews = reviewService.getAllReviews(companyId);
        return ResponseEntity.ok(reviews);
    }

    @PostMapping("/reviews")
    public ResponseEntity<String> addReview(@PathVariable Long companyId, @RequestBody Review review) {
        // Implementation for adding a review
        boolean isReviewSaved =  reviewService.addReview(companyId, review);
        if(isReviewSaved){
            return ResponseEntity.status(201).body("Review Added successfully");
        }
        else return ResponseEntity.status(404).body("Review Not Saved");
    }

    @GetMapping("/reviews/{reviewId}")
    public ResponseEntity<Review> getReviewById(@PathVariable Long companyId, @PathVariable Long reviewId) {
        return ResponseEntity.ok(reviewService.getReview(companyId, reviewId));
    }

    @PutMapping("/reviews/{reviewId}")
    public ResponseEntity<String> updateReview(@PathVariable Long companyId, @PathVariable Long reviewId, @RequestBody Review updatedReview) {
        boolean isReviewUpdated = reviewService.updateReview(companyId, reviewId, updatedReview);
        if (isReviewUpdated) {
            return new ResponseEntity<>("Review updated successfully", HttpStatus.OK);
        }
        else return new ResponseEntity<>("Review not updated", HttpStatus.NOT_FOUND);
    }

    @DeleteMapping("/reviews/{reviewId}")
    public ResponseEntity<String> deleteReview(@PathVariable Long companyId, @PathVariable Long reviewId) {

        boolean isReviewDeleted = reviewService.deleteReview(companyId, reviewId);
        if (isReviewDeleted) {
            return new ResponseEntity<>("Review deleted successfully", HttpStatus.OK);
        }
        else return new ResponseEntity<>("Review not deleted", HttpStatus.NOT_FOUND);
    }
}
