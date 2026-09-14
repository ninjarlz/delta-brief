package pl.tul.deltabrief.briefing.adapter.out.rss;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pl.tul.deltabrief.briefing.application.port.out.FeedSource;
import pl.tul.deltabrief.briefing.application.port.out.TopicSearchFeedProvider;

/**
 * Google News' free, unauthenticated (but unofficial/undocumented) RSS
 * search endpoint. Plain keyword search, not a semantic/AI query — Google's
 * own relevance ranking beats naive substring matching against already
 * -fetched titles, but there's no way to pass steering intent (e.g. a
 * topic's description) through it, only the query text itself. {@code
 * baseUrl} is externalized (not hard-coded) specifically so tests can point
 * it at WireMock instead of the real endpoint, the same pattern already used
 * for {@code spring.ai.openai.base-url} — no automated test makes a real
 * network call (see plan.md's WireMock stubbing points).
 */
@Component
class GoogleNewsSearchFeedProvider implements TopicSearchFeedProvider {

	private static final String SEARCH_PATH_TEMPLATE = "/rss/search?q=%s&hl=en-US&gl=US&ceid=US:en";

	private final String baseUrl;

	GoogleNewsSearchFeedProvider(@Value("${app.google-news.base-url:https://news.google.com}") String baseUrl) {
		this.baseUrl = baseUrl;
	}

	@Override
	public FeedSource searchFeedFor(String topicName) {
		String encodedQuery = URLEncoder.encode(topicName, StandardCharsets.UTF_8);
		return new FeedSource("Google News: " + topicName, baseUrl + SEARCH_PATH_TEMPLATE.formatted(encodedQuery));
	}

}
