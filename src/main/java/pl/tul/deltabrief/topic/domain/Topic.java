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
	private Frequency frequency;
	private Integer preferredHour;
	private Instant nextDueAt;
	private Instant lastScheduledAttemptAt;
	private ScheduledRunStatus lastScheduledStatus;
	private boolean emailEnabled;

	public Topic(TopicId id, UserId userId, String name, CategoryId categoryId, String description, Instant createdAt,
			Frequency frequency, Integer preferredHour, Instant nextDueAt, Instant lastScheduledAttemptAt,
			ScheduledRunStatus lastScheduledStatus, boolean emailEnabled) {
		this.id = id;
		this.userId = userId;
		this.name = name;
		this.categoryId = categoryId;
		this.description = description;
		this.createdAt = createdAt;
		this.frequency = frequency;
		this.preferredHour = preferredHour;
		this.nextDueAt = nextDueAt;
		this.lastScheduledAttemptAt = lastScheduledAttemptAt;
		this.lastScheduledStatus = lastScheduledStatus;
		this.emailEnabled = emailEnabled;
	}

	/**
	 * @param description the user's optional observation-goal note (FR-004)
	 * — {@code null} if not provided. Fed into the briefing generation
	 * prompt as extra context once set; purely descriptive otherwise.
	 */
	public static Topic create(UserId userId, String name, CategoryId categoryId, String description,
			Instant createdAt) {
		return new Topic(null, userId, name, categoryId, description, createdAt, Frequency.DAILY, null, null, null,
				null, true);
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

	/**
	 * Sets the topic's cadence and email-delivery preference — used both at
	 * creation (with a freshly computed {@code nextDueAt}) and from the edit
	 * flow (recomputing it from the topic's current schedule anchor).
	 * {@code emailEnabled} rides along here since it changes at exactly the
	 * same two call sites as {@code frequency}/{@code preferredHour}. Does
	 * not touch {@code lastScheduledAttemptAt}/{@code lastScheduledStatus} —
	 * those only change as an actual scheduled attempt happens.
	 */
	public void applySchedule(Frequency frequency, Integer preferredHour, boolean emailEnabled, Instant nextDueAt) {
		this.frequency = Objects.requireNonNull(frequency);
		this.preferredHour = preferredHour;
		this.emailEnabled = emailEnabled;
		this.nextDueAt = nextDueAt;
	}

	/**
	 * A scheduled generation attempt succeeded: advance the schedule and
	 * record the attempt.
	 */
	public void recordScheduledSuccess(Instant attemptedAt, Instant nextDueAt) {
		this.lastScheduledAttemptAt = attemptedAt;
		this.lastScheduledStatus = ScheduledRunStatus.SUCCESS;
		this.nextDueAt = nextDueAt;
	}

	/**
	 * A scheduled generation attempt failed: record the attempt but leave
	 * {@code nextDueAt} unchanged, so the topic stays due and the next poll
	 * cycle retries it — no backoff, no auto-pause.
	 */
	public void recordScheduledFailure(Instant attemptedAt) {
		this.lastScheduledAttemptAt = attemptedAt;
		this.lastScheduledStatus = ScheduledRunStatus.FAILURE;
	}

}
