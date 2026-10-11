package org.remus.giteabot.repository.model;

import java.util.List;

/** A paginated comment result; incomplete scans must not be used to infer absence. */
public record CommentScan<T>(List<T> comments, boolean complete) {

    public CommentScan {
        comments = comments == null ? List.of() : List.copyOf(comments);
    }
}
