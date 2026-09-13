package pl.tul.deltabrief.topic.domain;

/**
 * A preset RSS/Atom source belonging to a {@link Category}, seeded via
 * migration. Plain reference data — no behavior or invariants beyond its
 * fields. Not yet read anywhere in the app (no port/adapter exists for it
 * beyond the JPA entity backing its table) — this slice only needs
 * categories for topic creation; source ingestion arrives with briefing
 * generation.
 */
public class Source {

	private final SourceId id;
	private final CategoryId categoryId;
	private final String name;
	private final String feedUrl;

	public Source(SourceId id, CategoryId categoryId, String name, String feedUrl) {
		this.id = id;
		this.categoryId = categoryId;
		this.name = name;
		this.feedUrl = feedUrl;
	}

	public SourceId id() {
		return id;
	}

	public CategoryId categoryId() {
		return categoryId;
	}

	public String name() {
		return name;
	}

	public String feedUrl() {
		return feedUrl;
	}

}
