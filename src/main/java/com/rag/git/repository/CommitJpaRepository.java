package com.rag.git.repository;

import com.rag.git.model.CommitRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CommitJpaRepository extends JpaRepository<CommitRecord, String> {

    boolean existsByRepositoryIdAndCommitHash(String repositoryId, String commitHash);

    List<CommitRecord> findByRepositoryIdOrderByCommitDateDesc(String repositoryId);

    long countByRepositoryId(String repositoryId);

    // ── Date-based report ────────────────────────────────────────────────────
    List<CommitRecord> findByCommitDateBetweenOrderByCommitDateDesc(LocalDateTime start, LocalDateTime end);

    List<CommitRecord> findByRepositoryIdAndCommitDateBetweenOrderByCommitDateDesc(
            String repositoryId, LocalDateTime start, LocalDateTime end);

    long countByCommitDateBetween(LocalDateTime start, LocalDateTime end);

    long countByRepositoryIdAndCommitDateBetween(String repositoryId, LocalDateTime start, LocalDateTime end);

    // ── Author breakdown for a sprint / date range ──────────────────────────
    @Query("""
            SELECT c.authorName, c.authorEmail, COUNT(c)
            FROM CommitRecord c
            WHERE c.commitDate BETWEEN :start AND :end
            GROUP BY c.authorName, c.authorEmail
            ORDER BY COUNT(c) DESC
            """)
    List<Object[]> countByAuthorBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("""
            SELECT c.authorName, c.authorEmail, COUNT(c)
            FROM CommitRecord c
            WHERE c.repositoryId = :repositoryId AND c.commitDate BETWEEN :start AND :end
            GROUP BY c.authorName, c.authorEmail
            ORDER BY COUNT(c) DESC
            """)
    List<Object[]> countByAuthorBetweenForRepository(@Param("repositoryId") String repositoryId,
                                                       @Param("start") LocalDateTime start,
                                                       @Param("end") LocalDateTime end);
}
