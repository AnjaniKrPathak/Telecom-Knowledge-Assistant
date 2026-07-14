package com.rag.repository;

import com.rag.model.IngestionFailureRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IngestionFailureRepository extends JpaRepository<IngestionFailureRecord, String> {
    List<IngestionFailureRecord> findByBatchId(String batchId);
}
