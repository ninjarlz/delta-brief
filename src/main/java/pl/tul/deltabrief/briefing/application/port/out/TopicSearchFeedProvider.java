package pl.tul.deltabrief.briefing.application.port.out;

/**
 * Builds a fetchable {@link FeedSource} that searches for a topic by name,
 * supplementing the topic's category-curated sources ({@link
 * FeedSourceCatalog}) with results actually targeted at the topic itself —
 * the curated feeds are category-wide (e.g. general "World News") and carry
 * no relevance signal for a specific topic like "War in Ukraine" within that
 * category. The curated feeds are kept alongside this, not replaced by it —
 * this exists to improve relevance, not to be the sole source.
 */
public interface TopicSearchFeedProvider {

	FeedSource searchFeedFor(String topicName);

}
