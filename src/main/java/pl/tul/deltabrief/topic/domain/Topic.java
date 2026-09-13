package pl.tul.deltabrief.topic.domain;

import java.time.Instant;
import java.util.Objects;
import pl.tul.deltabrief.auth.domain.UserId;

/**
 * The topic aggregate: a user's watched subject and the preset category
 * whose sources it draws from. Category is fixed at creation for v1 — no
 * behavior here to change it.
 */
public class Topic {

	private TopicId id;
	private final UserId userId;
	private final String name;
	private final CategoryId categoryId;
	private final Instant createdAt;

	public Topic(TopicId id, UserId userId, String name, CategoryId categoryId, Instant createdAt) {
		this.id = id;
		this.userId = userId;
		this.name = name;
		this.categoryId = categoryId;
		this.createdAt = createdAt;
	}

	public static Topic create(UserId userId, String name, CategoryId categoryId, Instant createdAt) {
		return new Topic(null, userId, name, categoryId, createdAt);
	}

	public TopicId id() {
		return id;
	}

	public void assignId(TopicId id) {
		this.id = Objects.requireNonNull(id);
	}

	public UserId userId() {
		return userId;
	}

	public String name() {
		return name;
	}

	public CategoryId categoryId() {
		return categoryId;
	}

	public Instant createdAt() {
		return createdAt;
	}

}
