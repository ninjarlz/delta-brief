package pl.tul.deltabrief.topic.application.port.out;

import java.util.List;
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
	 * @return whether a row was actually deleted — {@code false} means the
	 * topic either doesn't exist or isn't owned by {@code userId}; callers
	 * must treat both cases identically (no information leak).
	 */
	boolean deleteByIdAndUserId(TopicId id, UserId userId);

}
