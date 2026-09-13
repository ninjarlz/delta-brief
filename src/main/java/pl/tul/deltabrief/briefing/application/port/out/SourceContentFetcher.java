package pl.tul.deltabrief.briefing.application.port.out;

import java.util.List;

/**
 * Port for fetching a {@link FeedSource}'s recent content. Throws {@link
 * SourceUnavailableException} on any fetch/parse failure rather than
 * returning an empty list, so the caller can distinguish "genuinely no new
 * items" from "couldn't reach this source" and — per plan.md — proceed with
 * whatever other sources succeeded instead of failing the whole generation.
 *
 * <p><b>Trust assumption:</b> {@code feedUrl} is treated as a trusted,
 * migration-seeded value (see {@link FeedSourceCatalog}) — no user-facing
 * path writes to the {@code sources} table today. If a future slice ever
 * lets users submit their own feed URLs, revisit this fetcher for
 * SSRF-relevant hardening (scheme/host allowlisting) before that ships.
 */
public interface SourceContentFetcher {

	List<FetchedItem> fetch(FeedSource source);

	class SourceUnavailableException extends RuntimeException {

		public SourceUnavailableException(FeedSource source, Throwable cause) {
			super("Source unavailable: %s (%s)".formatted(source.name(), source.feedUrl()), cause);
		}

	}

}
