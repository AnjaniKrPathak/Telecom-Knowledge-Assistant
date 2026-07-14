package com.rag.repository;

import com.rag.model.IngestionBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IngestionBatchRepository extends JpaRepository<IngestionBatch, String> {
}
