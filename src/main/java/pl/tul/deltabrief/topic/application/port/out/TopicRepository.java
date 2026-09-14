package pl.tul.deltabrief.topic.application.port.out;

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

}
