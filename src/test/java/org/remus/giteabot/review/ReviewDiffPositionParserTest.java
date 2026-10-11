package org.remus.giteabot.review;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.model.DiffSide;
import org.remus.giteabot.repository.model.ReviewAnchorComment;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewDiffPositionParserTest {

    private static final String MULTI_HUNK = """
            diff --git a/src/App.java b/src/App.java
            index 111..222 100644
            --- a/src/App.java
            +++ b/src/App.java
            @@ -1,4 +1,5 @@
             package app;
            -import old.Thing;
            +import new.Thing;
            +import new.Other;
             
             class App {
            @@ -20,3 +21,2 @@ class App {
             void a() {}
            -void b() {}
             void c() {}
            """;

    private static ReviewDocument.Finding finding(String path, DiffSide side, int line) {
        return new ReviewDocument.Finding("f1", "body", path, side, line, null, null);
    }

    private static Optional<ReviewAnchorComment> anchor(String diff, String path, DiffSide side, int line) {
        return ReviewDiffPositionParser.parse(diff).anchor(finding(path, side, line), "body");
    }

    @Test
    void mapsAddedRemovedAndContextLinesAcrossMultipleHunks() {
        assertThat(anchor(MULTI_HUNK, "src/App.java", DiffSide.NEW, 2)).hasValueSatisfying(a -> {
            assertThat(a.side()).isEqualTo(DiffSide.NEW);
            assertThat(a.oldLine()).isNull();
            assertThat(a.newLine()).isEqualTo(2);
        });
        assertThat(anchor(MULTI_HUNK, "src/App.java", DiffSide.OLD, 2)).hasValueSatisfying(a -> {
            assertThat(a.oldLine()).isEqualTo(2);
            assertThat(a.newLine()).isNull();
        });
        // Context line in shifted second hunk: old 22 == new 22? old 20->new 21, old 21 removed, old 22->new 22.
        assertThat(anchor(MULTI_HUNK, "src/App.java", DiffSide.NEW, 21)).hasValueSatisfying(a ->
                assertThat(a.oldLine()).isEqualTo(20));
        assertThat(anchor(MULTI_HUNK, "src/App.java", DiffSide.OLD, 21)).hasValueSatisfying(a ->
                assertThat(a.newLine()).isNull());
        assertThat(anchor(MULTI_HUNK, "src/App.java", DiffSide.OLD, 22)).hasValueSatisfying(a ->
                assertThat(a.newLine()).isEqualTo(22));
        // Blank context line (whitespace stripped) still counts.
        assertThat(anchor(MULTI_HUNK, "src/App.java", DiffSide.NEW, 4)).hasValueSatisfying(a ->
                assertThat(a.oldLine()).isEqualTo(3));
    }

    @Test
    void rejectsLinesOutsideHunksAndOnWrongSide() {
        assertThat(anchor(MULTI_HUNK, "src/App.java", DiffSide.NEW, 10)).isEmpty();
        assertThat(anchor(MULTI_HUNK, "src/App.java", DiffSide.OLD, 23)).isEmpty();
        assertThat(anchor(MULTI_HUNK, "src/App.java", DiffSide.NEW, 0)).isEmpty();
        assertThat(anchor(MULTI_HUNK, "src/Missing.java", DiffSide.NEW, 2)).isEmpty();
    }

    @Test
    void acceptsCommonPathPrefixesFromTheModel() {
        assertThat(anchor(MULTI_HUNK, "b/src/App.java", DiffSide.NEW, 2)).isPresent();
        assertThat(anchor(MULTI_HUNK, "./src/App.java", DiffSide.NEW, 2)).isPresent();
    }

    @Test
    void handlesRenamesAddedAndDeletedFiles() {
        String diff = """
                diff --git a/old/Name.java b/new/Name.java
                similarity index 90%
                rename from old/Name.java
                rename to new/Name.java
                --- a/old/Name.java
                +++ b/new/Name.java
                @@ -3,2 +3,2 @@
                 keep
                -gone
                +added
                diff --git a/Added.txt b/Added.txt
                new file mode 100644
                --- /dev/null
                +++ b/Added.txt
                @@ -0,0 +1,2 @@
                +one
                +two
                diff --git a/Deleted.txt b/Deleted.txt
                deleted file mode 100644
                --- a/Deleted.txt
                +++ /dev/null
                @@ -1 +0,0 @@
                -bye
                \\ No newline at end of file
                """;
        assertThat(anchor(diff, "new/Name.java", DiffSide.NEW, 4)).hasValueSatisfying(a -> {
            assertThat(a.path()).isEqualTo("new/Name.java");
            assertThat(a.oldPath()).isEqualTo("old/Name.java");
        });
        assertThat(anchor(diff, "old/Name.java", DiffSide.OLD, 4)).hasValueSatisfying(a ->
                assertThat(a.path()).isEqualTo("new/Name.java"));
        assertThat(anchor(diff, "Added.txt", DiffSide.NEW, 2)).isPresent();
        assertThat(anchor(diff, "Added.txt", DiffSide.OLD, 1)).isEmpty();
        assertThat(anchor(diff, "Deleted.txt", DiffSide.OLD, 1)).hasValueSatisfying(a -> {
            assertThat(a.path()).isEqualTo("Deleted.txt");
            assertThat(a.newLine()).isNull();
        });
        assertThat(anchor(diff, "Deleted.txt", DiffSide.NEW, 1)).isEmpty();
    }

    @Test
    void decodesQuotedPaths() {
        String diff = """
                diff --git "a/dir/caf\\303\\251 x.txt" "b/dir/caf\\303\\251 x.txt"
                --- "a/dir/caf\\303\\251 x.txt"
                +++ "b/dir/caf\\303\\251 x.txt"
                @@ -1 +1 @@
                -a
                +b
                """;
        assertThat(anchor(diff, "dir/café x.txt", DiffSide.NEW, 1)).isPresent();
    }

    @Test
    void binaryTruncatedAndMalformedFilesAreNeverCommentable() {
        String binary = """
                diff --git a/img.png b/img.png
                Binary files a/img.png and b/img.png differ
                """;
        assertThat(anchor(binary, "img.png", DiffSide.NEW, 1)).isEmpty();

        String truncated = """
                diff --git a/T.java b/T.java
                --- a/T.java
                +++ b/T.java
                @@ -1,5 +1,5 @@
                 a
                -b
                +c""";
        assertThat(anchor(truncated, "T.java", DiffSide.NEW, 2)).isEmpty();

        String malformedHeader = """
                diff --git a/M.java b/M.java
                --- a/M.java
                +++ b/M.java
                @@ garbage @@
                +x
                """;
        assertThat(anchor(malformedHeader, "M.java", DiffSide.NEW, 1)).isEmpty();

        String headerless = """
                @@ -1,1 +1,1 @@
                -a
                +b
                """;
        assertThat(ReviewDiffPositionParser.parse(headerless).file("anything")).isEmpty();

        String cutAfterHeader = "diff --git a/H.java b/H.java\n--- a/H.java\n+++ b/H.java\n@@ -1 +1 @@\n";
        assertThat(anchor(cutAfterHeader, "H.java", DiffSide.NEW, 1)).isEmpty();

        String cutAfterContext = "diff --git a/C.java b/C.java\n--- a/C.java\n+++ b/C.java\n@@ -1,2 +1,2 @@\n a\n";
        assertThat(anchor(cutAfterContext, "C.java", DiffSide.NEW, 1)).isEmpty();
    }

    @Test
    void strippedBlankContextLineMidHunkStillCountsAsContext() {
        String diff = "diff --git a/S.java b/S.java\n--- a/S.java\n+++ b/S.java\n@@ -1,3 +1,3 @@\n a\n\n-b\n+c\n";
        assertThat(anchor(diff, "S.java", DiffSide.NEW, 3)).isPresent();
    }

    @Test
    void malformedOctalEscapeInQuotedPathDoesNotAbortParsing() {
        String diff = "diff --git \"a/x\\19.txt\" \"b/x\\19.txt\"\n--- \"a/x\\19.txt\"\n+++ \"b/x\\19.txt\"\n"
                + "@@ -1 +1 @@\n-a\n+b\n"
                + "diff --git a/Ok.java b/Ok.java\n--- a/Ok.java\n+++ b/Ok.java\n@@ -1 +1 @@\n-a\n+b\n";
        assertThat(anchor(diff, "Ok.java", DiffSide.NEW, 1)).isPresent();
    }

    @Test
    void emptyDiffHasNoPositions() {
        assertThat(ReviewDiffPositionParser.parse("").isEmpty()).isTrue();
        assertThat(ReviewDiffPositionParser.parse(null).isEmpty()).isTrue();
    }
}
