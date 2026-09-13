package pl.tul.deltabrief.briefing.domain;

import java.time.Instant;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * One piece of source content ingested for a {@link Briefing} — a child
 * entity of that aggregate, not its own aggregate root: it has no lifecycle
 * independent of the briefing it was ingested for, and is always created,
 * persisted, and loaded together with it. Also doubles as the rendered
 * "sources" section of the briefing (FR-010) and as the numbered citation
 * list the generation prompt instructs the model to cite by index.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public class IngestedItem {

	private final String sourceName;
	private final String title;
	private final String link;
	private final Instant publishedAt;
	private final Instant fetchedAt;

}
