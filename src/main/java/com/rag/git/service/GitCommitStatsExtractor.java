package com.rag.git.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Computes files-changed / insertions / deletions for a single commit by diffing its tree
 * against its first parent's tree (or against an empty tree for the very first commit).
 * <p>
 * Merge commits (more than one parent) are skipped and reported as zero-stat — diffing a merge
 * against "its" changes is inherently ambiguous (which parent?), and most merge commits don't
 * carry their own line changes anyway.
 */
@Slf4j
final class GitCommitStatsExtractor {

    private GitCommitStatsExtractor() {
    }

    record Stats(int filesChanged, int insertions, int deletions) {
        static final Stats EMPTY = new Stats(0, 0, 0);
    }

    static Stats compute(Repository repository, RevWalk revWalk, RevCommit commit) {
        try {
            RevCommit[] parents = commit.getParents();
            if (parents.length > 1) {
                return Stats.EMPTY; // merge commit — ambiguous, skip
            }

            AbstractTreeIterator oldTreeIter = parents.length == 0
                    ? new EmptyTreeIterator()
                    : treeIteratorFor(repository, revWalk.parseCommit(parents[0].getId()));
            AbstractTreeIterator newTreeIter = treeIteratorFor(repository, commit);

            try (DiffFormatter formatter = new DiffFormatter(OutputStream.nullOutputStream())) {
                formatter.setRepository(repository);
                formatter.setDetectRenames(true);
                List<DiffEntry> diffs = formatter.scan(oldTreeIter, newTreeIter);

                int insertions = 0;
                int deletions = 0;
                for (DiffEntry entry : diffs) {
                    FileHeader header = formatter.toFileHeader(entry);
                    for (Edit edit : header.toEditList()) {
                        insertions += edit.getEndB() - edit.getBeginB();
                        deletions += edit.getEndA() - edit.getBeginA();
                    }
                }
                return new Stats(diffs.size(), insertions, deletions);
            }
        } catch (IOException e) {
            log.debug("Could not compute diff stats for commit {}: {}", commit.getName(), e.getMessage());
            return Stats.EMPTY;
        }
    }

    private static AbstractTreeIterator treeIteratorFor(Repository repository, RevCommit commit) throws IOException {
        CanonicalTreeParser treeParser = new CanonicalTreeParser();
        try (ObjectReader reader = repository.newObjectReader()) {
            treeParser.reset(reader, commit.getTree().getId());
        }
        return treeParser;
    }
}
