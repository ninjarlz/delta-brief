package pl.tul.deltabrief.briefing.adapter.out.rss;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.tul.deltabrief.briefing.application.port.out.FeedSource;
import pl.tul.deltabrief.briefing.application.port.out.FetchedItem;
import pl.tul.deltabrief.briefing.application.port.out.SourceContentFetcher.SourceUnavailableException;

/**
 * {@code RomeSourceContentFetcher} has no Spring dependencies, so this uses
 * WireMock's plain JUnit 5 extension (no Spring context) rather than booting
 * a full {@code @SpringBootTest} — see plan.md's WireMock stubbing points:
 * {@code FeedSource} carries the feed URL as a plain value, so a test
 * constructs one pointing directly at WireMock's own base URL and stubs
 * that path. No real network call to any live feed happens in this test.
 */
@WireMockTest
class RomeSourceContentFetcherTests {

	private static final String VALID_RSS = """
			<?xml version="1.0" encoding="UTF-8"?>
			<rss version="2.0">
			  <channel>
			    <title>Test Feed</title>
			    <item>
			      <title>Older item</title>
			      <link>https://example.com/older</link>
			      <pubDate>Mon, 01 Jan 2024 00:00:00 GMT</pubDate>
			    </item>
			    <item>
			      <title>Newer item</title>
			      <link>https://example.com/newer</link>
			      <pubDate>Tue, 02 Jan 2024 00:00:00 GMT</pubDate>
			    </item>
			  </channel>
			</rss>
			""";

	private final RomeSourceContentFetcher fetcher = new RomeSourceContentFetcher();

	@Test
	void fetchesAndParsesEntriesNewestFirst(WireMockRuntimeInfo wireMock) {
		stubFor(get(urlEqualTo("/feed.xml"))
				.willReturn(aResponse().withHeader("Content-Type", "application/rss+xml").withBody(VALID_RSS)));
		FeedSource source = new FeedSource("Test Source", wireMock.getHttpBaseUrl() + "/feed.xml");

		List<FetchedItem> items = fetcher.fetch(source);

		assertThat(items).hasSize(2);
		assertThat(items.get(0).title()).isEqualTo("Newer item");
		assertThat(items.get(1).title()).isEqualTo("Older item");
	}

	@Test
	void raisesSourceUnavailableForMalformedXml(WireMockRuntimeInfo wireMock) {
		stubFor(get(urlEqualTo("/broken.xml")).willReturn(aResponse().withBody("not xml at all")));
		FeedSource source = new FeedSource("Broken Source", wireMock.getHttpBaseUrl() + "/broken.xml");

		assertThatExceptionOfType(SourceUnavailableException.class).isThrownBy(() -> fetcher.fetch(source));
	}

	@Test
	void raisesSourceUnavailableForUnreachableHost() {
		FeedSource source = new FeedSource("Unreachable Source", "http://localhost:1/feed.xml");

		assertThatExceptionOfType(SourceUnavailableException.class).isThrownBy(() -> fetcher.fetch(source));
	}

}
