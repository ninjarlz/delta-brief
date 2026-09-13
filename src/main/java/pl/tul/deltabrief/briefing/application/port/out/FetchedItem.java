package pl.tul.deltabrief.briefing.application.port.out;

import java.time.Instant;

/**
 * One entry read from a {@link FeedSource}'s feed — not every feed entry
 * carries a reliable publish date, so {@code publishedAt} may be {@code
 * null}.
 */
public record FetchedItem(String title, String link, Instant publishedAt) {
}
