package pl.tul.deltabrief.topic.domain;

import java.time.Instant;
import java.util.Objects;
import lombok.Getter;
import lombok.experimental.Accessors;
import pl.tul.deltabrief.auth.domain.UserId;

/**
 * The topic aggregate: a user's watched subject and the preset category
 * whose sources it draws from. Category is fixed at creation for v1 — no
 * behavior here to change it.
 */
@Getter
@Accessors(fluent = true)
public class Topic {

	private TopicId id;
	private final UserId userId;
	private final String name;
	private final CategoryId categoryId;
	private final String description;
	private final Instant createdAt;

	public Topic(TopicId id, UserId userId, String name, CategoryId categoryId, String description,
			Instant createdAt) {
		this.id = id;
		this.userId = userId;
		this.name = name;
		this.categoryId = categoryId;
		this.description = description;
		this.createdAt = createdAt;
	}

	/**
	 * @param description the user's optional observation-goal note (FR-004)
	 * — {@code null} if not provided. Fed into the briefing generation
	 * prompt as extra context once set; purely descriptive otherwise.
	 */
	public static Topic create(UserId userId, String name, CategoryId categoryId, String description,
			Instant createdAt) {
		return new Topic(null, userId, name, categoryId, description, createdAt);
	}

	/**
	 * Convenience overload for the common case of no description — most
	 * existing call sites (and every test that doesn't care about FR-004)
	 * use this rather than passing {@code null} explicitly everywhere.
	 */
	public static Topic create(UserId userId, String name, CategoryId categoryId, Instant createdAt) {
		return create(userId, name, categoryId, null, createdAt);
	}

	public void assignId(TopicId id) {
		this.id = Objects.requireNonNull(id);
	}

}
