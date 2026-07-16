package com.rag.repository;

import com.rag.model.FeedbackRating;
import com.rag.model.QueryFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface QueryFeedbackRepository extends JpaRepository<QueryFeedback, String> {

    Optional<QueryFeedback> findByInteractionId(String interactionId);

    long countByRating(FeedbackRating rating);

    List<QueryFeedback> findAllByOrderByCreatedAtDesc();
}
