package app.senyap.posts;

/** Post + {@code hot_score} untuk feed Populer. */
public record HotCandidate(long postId, double hotScore) {
}
