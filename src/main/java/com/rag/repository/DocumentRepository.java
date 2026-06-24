package com.rag.repository;

import com.rag.model.DocumentRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<DocumentRecord, String> {
    List<DocumentRecord> findByType(String type);
    boolean existsBySource(String source);
}
