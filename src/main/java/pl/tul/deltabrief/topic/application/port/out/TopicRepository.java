package pl.tul.deltabrief.topic.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import pl.tul.deltabrief.auth.domain.UserId;
import pl.tul.deltabrief.topic.domain.Topic;
import pl.tul.deltabrief.topic.domain.TopicId;

/**
 * Port the application layer depends on for {@link Topic} persistence,
 * implemented by the persistence adapter — keeps {@code application} free
 * of JPA imports. Owner-scoping (filtering/deleting by {@link UserId}) must
 * happen in the query itself, not by fetching then checking in application
 * code — see plan.md's Critical Implementation Details.
 */
public interface TopicRepository {

	Topic save(Topic topic);

	List<Topic> findAllByUserId(UserId userId);

	boolean existsByUserIdAndNameIgnoreCase(UserId userId, String name);

	long countByUserId(UserId userId);

	/**
	 * Owner-scoped existence + name/category lookup in one query — used by
	 * {@code briefing} to verify topic ownership and resolve the topic name
	 * (for the generation prompt) and category (to resolve sources) without
	 * ever loading the full {@link Topic} aggregate outside this module (see
	 * plan.md's Critical Implementation Details for why {@code briefing}
	 * never imports {@link Topic}).
	 *
	 * @return empty if the topic doesn't exist or isn't owned by {@code
	 * userId} — both cases are indistinguishable to the caller, same
	 * information-leak avoidance as {@link #deleteByIdAndUserId}.
	 */
	Optional<TopicSummary> findSummaryByIdAndUserId(TopicId id, UserId userId);

	/**
	 * Full owner-scoped topic lookup — unlike {@link #findSummaryByIdAndUserId},
	 * which returns only a lightweight cross-module view, this returns the
	 * whole aggregate for callers (the schedule edit flow) that need to
	 * mutate and re-save it via {@link #save}.
	 *
	 * @return empty if the topic doesn't exist or isn't owned by {@code userId}
	 */
	Optional<Topic> findByIdAndUserId(TopicId id, UserId userId);

	/**
	 * @return whether a row was actually deleted — {@code false} means the
	 * topic either doesn't exist or isn't owned by {@code userId}; callers
	 * must treat both cases identically (no information leak).
	 */
	boolean deleteByIdAndUserId(TopicId id, UserId userId);

	/**
	 * Topics due for scheduled generation (FR-009) as of {@code now} — every
	 * topic whose {@code nextDueAt} has passed.
	 */
	List<DueTopic> findDueForScheduledGeneration(Instant now);

	/**
	 * Records a successful generation's outcome for scheduling purposes —
	 * called after every generation (manual, onboarding, or scheduled)
	 * persists its briefing, so the schedule advances the same way
	 * regardless of trigger. Recomputes and persists {@code nextDueAt} from
	 * the topic's own frequency/preferredTime, anchored at
	 * {@code generatedAt}, and marks the attempt a success. Silently no-ops
	 * if the topic no longer exists (a narrow, harmless race with topic
	 * deletion).
	 */
	void recordSuccessfulGeneration(TopicId id, Instant generatedAt);

	/**
	 * Records a failed <em>scheduled</em> generation attempt — never called
	 * for a manual/onboarding failure, which already surfaces synchronously
	 * as an HTTP error to the user. Leaves {@code nextDueAt} unchanged so
	 * the topic stays due and the next poll cycle retries it — no backoff,
	 * no auto-pause.
	 */
	void recordFailedScheduledGeneration(TopicId id, Instant attemptedAt);

}
