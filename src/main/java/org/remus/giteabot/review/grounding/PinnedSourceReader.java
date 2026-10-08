package org.remus.giteabot.review.grounding;

import org.remus.giteabot.repository.RepositoryApiClient;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Reads repository files from one immutable commit revision, caching each file for a run. */
public final class PinnedSourceReader {

    private static final Pattern COMMIT_SHA = Pattern.compile("(?i)[0-9a-f]{40}|[0-9a-f]{64}");

    private final RepositoryApiClient repositoryApiClient;
    private final String owner;
    private final String repo;
    private final String sha;
    private final Map<String, String> files = new ConcurrentHashMap<>();

    public PinnedSourceReader(RepositoryApiClient repositoryApiClient, String owner, String repo, String sha) {
        this.repositoryApiClient = Objects.requireNonNull(repositoryApiClient, "repositoryApiClient");
        this.owner = requireText(owner, "owner");
        this.repo = requireText(repo, "repo");
        if (sha == null || !COMMIT_SHA.matcher(sha).matches()) {
            throw new IllegalArgumentException("A full commit SHA is required");
        }
        this.sha = sha;
    }

    /** Returns file contents fetched only at this reader's commit SHA. */
    public String read(String path) {
        String filePath = requireText(path, "path");
        try {
            return files.computeIfAbsent(filePath, this::fetch);
        } catch (RevisionUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RevisionUnavailableException(filePath, sha, e);
        }
    }

    public String revisionSha() {
        return sha;
    }

    private String fetch(String path) {
        try {
            String content = repositoryApiClient.getFileContent(owner, repo, path, sha);
            if (content == null || content.indexOf('\0') >= 0) {
                throw new IllegalStateException("File content is unavailable or binary");
            }
            return content;
        } catch (RuntimeException e) {
            throw new RevisionUnavailableException(path, sha, e);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** Indicates that a file could not be read from the requested immutable revision. */
    public static final class RevisionUnavailableException extends RuntimeException {
        public RevisionUnavailableException(String path, String sha, Throwable cause) {
            super("Could not read " + path + " at commit " + sha, cause);
        }
    }
}
