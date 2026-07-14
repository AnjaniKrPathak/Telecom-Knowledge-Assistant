package com.rag.git.repository;

import com.rag.git.model.Sprint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SprintJpaRepository extends JpaRepository<Sprint, String> {
    List<Sprint> findByRepositoryIdIsNullOrRepositoryId(String repositoryId);
    List<Sprint> findAllByOrderByStartDateDesc();
}
