package pl.tul.deltabrief.topic.domain;

/**
 * A preset topic category (e.g. "World News"), seeded via migration. Plain
 * reference data — no behavior or invariants beyond its fields.
 */
public class Category {

	private final CategoryId id;
	private final String name;

	public Category(CategoryId id, String name) {
		this.id = id;
		this.name = name;
	}

	public CategoryId id() {
		return id;
	}

	public String name() {
		return name;
	}

}
