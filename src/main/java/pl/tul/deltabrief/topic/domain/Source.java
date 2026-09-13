package pl.tul.deltabrief.topic.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * A preset RSS/Atom source belonging to a {@link Category}, seeded via
 * migration. Plain reference data — no behavior or invariants beyond its
 * fields. Not yet read anywhere in the app (no port/adapter exists for it
 * beyond the JPA entity backing its table) — this slice only needs
 * categories for topic creation; source ingestion arrives with briefing
 * generation.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public class Source {

	private final SourceId id;
	private final CategoryId categoryId;
	private final String name;
	private final String feedUrl;

}
