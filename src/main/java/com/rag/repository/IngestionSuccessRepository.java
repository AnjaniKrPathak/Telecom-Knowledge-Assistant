package com.rag.repository;

import com.rag.model.IngestionSuccessRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IngestionSuccessRepository extends JpaRepository<IngestionSuccessRecord, String> {
    List<IngestionSuccessRecord> findByBatchId(String batchId);
}
