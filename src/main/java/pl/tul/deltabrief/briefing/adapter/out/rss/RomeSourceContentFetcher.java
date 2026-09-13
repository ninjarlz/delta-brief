package pl.tul.deltabrief.briefing.adapter.out.rss;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import pl.tul.deltabrief.briefing.application.port.out.FeedSource;
import pl.tul.deltabrief.briefing.application.port.out.FetchedItem;
import pl.tul.deltabrief.briefing.application.port.out.SourceContentFetcher;

/**
 * Fetches and parses a {@link FeedSource}'s RSS/Atom feed via Rome. Caps the
 * result at the most recent {@value #MAX_ITEMS_PER_SOURCE} entries to bound
 * prompt size, cost, and latency for the AI generation phase (see plan.md's
 * "Ingested items are capped per source").
 *
 * <p>Uses {@link HttpClient} rather than plain {@code URLConnection},
 * specifically for {@link HttpClient.Redirect#NORMAL} redirect handling.
 * Discovered via this plan's own manual verification step: at least one
 * real seeded source ({@code http://feeds.bbci.co.uk/...}, V6 migration)
 * 302-redirects to {@code https://}, and {@code HttpURLConnection} does not
 * follow cross-protocol redirects by default — it silently yields an empty
 * body instead of the feed.
 */
@Component
class RomeSourceContentFetcher implements SourceContentFetcher {

	private static final int MAX_ITEMS_PER_SOURCE = 10;
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

	private final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	@Override
	public List<FetchedItem> fetch(FeedSource source) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(source.feedUrl())).timeout(READ_TIMEOUT).GET()
					.build();
			HttpResponse<byte[]> response = httpClient.send(request, BodyHandlers.ofByteArray());
			if (response.statusCode() / 100 != 2) {
				throw new IOException("Unexpected HTTP status %d fetching %s".formatted(response.statusCode(),
						source.feedUrl()));
			}
			SyndFeed feed = new SyndFeedInput().build(new XmlReader(new ByteArrayInputStream(response.body())));
			return feed.getEntries().stream()
					.sorted(Comparator.comparing(RomeSourceContentFetcher::publishedInstantOrMin).reversed())
					.limit(MAX_ITEMS_PER_SOURCE)
					.map(RomeSourceContentFetcher::toFetchedItem)
					.toList();
		} catch (IOException | FeedException | IllegalArgumentException e) {
			throw new SourceUnavailableException(source, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new SourceUnavailableException(source, e);
		}
	}

	private static FetchedItem toFetchedItem(SyndEntry entry) {
		return new FetchedItem(entry.getTitle(), entry.getLink(), publishedInstant(entry));
	}

	private static Instant publishedInstantOrMin(SyndEntry entry) {
		return Optional.ofNullable(publishedInstant(entry)).orElse(Instant.MIN);
	}

	private static Instant publishedInstant(SyndEntry entry) {
		return entry.getPublishedDate() == null ? null : entry.getPublishedDate().toInstant();
	}

}
