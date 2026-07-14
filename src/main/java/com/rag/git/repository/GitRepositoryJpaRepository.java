package com.rag.git.repository;

import com.rag.git.model.GitRepositoryRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GitRepositoryJpaRepository extends JpaRepository<GitRepositoryRecord, String> {
    Optional<GitRepositoryRecord> findByRepoUrl(String repoUrl);
    boolean existsByRepoUrl(String repoUrl);
}
