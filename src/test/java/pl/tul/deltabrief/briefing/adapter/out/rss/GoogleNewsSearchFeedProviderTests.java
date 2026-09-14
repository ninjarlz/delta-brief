package pl.tul.deltabrief.briefing.adapter.out.rss;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import pl.tul.deltabrief.briefing.application.port.out.FeedSource;

/**
 * Pure URL-building logic, no network involved — see plan discussion:
 * verifies the search query is correctly encoded into the configured base
 * URL, not that Google News itself responds correctly (that's out of this
 * app's control).
 */
class GoogleNewsSearchFeedProviderTests {

	private final GoogleNewsSearchFeedProvider provider = new GoogleNewsSearchFeedProvider("https://news.example.com");

	@Test
	void buildsASearchFeedUrlAgainstTheConfiguredBaseUrl() {
		FeedSource source = provider.searchFeedFor("War in Ukraine");

		assertThat(source.name()).isEqualTo("Google News: War in Ukraine");
		assertThat(source.feedUrl())
				.isEqualTo("https://news.example.com/rss/search?q=War+in+Ukraine&hl=en-US&gl=US&ceid=US:en");
	}

	@Test
	void urlEncodesSpecialCharactersInTheTopicName() {
		FeedSource source = provider.searchFeedFor("AT&T / 5G?");

		assertThat(source.feedUrl()).contains("q=AT%26T+%2F+5G%3F");
	}

}
