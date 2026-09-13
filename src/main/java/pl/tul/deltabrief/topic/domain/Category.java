package pl.tul.deltabrief.topic.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * A preset topic category (e.g. "World News"), seeded via migration. Plain
 * reference data — no behavior or invariants beyond its fields.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public class Category {

	private final CategoryId id;
	private final String name;

}
