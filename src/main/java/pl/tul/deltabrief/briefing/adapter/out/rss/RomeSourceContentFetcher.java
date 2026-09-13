package pl.tul.deltabrief.briefing.adapter.out.rss;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
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
 */
@Component
class RomeSourceContentFetcher implements SourceContentFetcher {

	private static final int MAX_ITEMS_PER_SOURCE = 10;
	private static final int CONNECT_TIMEOUT_MS = 5000;
	private static final int READ_TIMEOUT_MS = 5000;

	@Override
	public List<FetchedItem> fetch(FeedSource source) {
		try {
			URLConnection connection = URI.create(source.feedUrl()).toURL().openConnection();
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);
			try (InputStream inputStream = connection.getInputStream()) {
				SyndFeed feed = new SyndFeedInput().build(new XmlReader(inputStream));
				return feed.getEntries().stream()
						.sorted(Comparator.comparing(RomeSourceContentFetcher::publishedInstantOrMin).reversed())
						.limit(MAX_ITEMS_PER_SOURCE)
						.map(RomeSourceContentFetcher::toFetchedItem)
						.toList();
			}
		} catch (IOException | FeedException | IllegalArgumentException e) {
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
