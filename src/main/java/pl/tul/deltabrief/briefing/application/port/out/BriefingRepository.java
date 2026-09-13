package pl.tul.deltabrief.briefing.application.port.out;

import java.util.List;
import java.util.Optional;
import pl.tul.deltabrief.briefing.domain.Briefing;
import pl.tul.deltabrief.briefing.domain.BriefingId;
import pl.tul.deltabrief.topic.domain.TopicId;

/**
 * Port the application layer depends on for {@link Briefing} persistence,
 * implemented by the persistence adapter — keeps {@code application} free
 * of JPA imports. {@link Briefing#ingestedItems()} is a child collection
 * persisted and loaded together with its parent, not a separate aggregate.
 */
public interface BriefingRepository {

	Briefing save(Briefing briefing);

	/**
	 * Full aggregate (including ingested items) — used both as delta
	 * generation context and to determine "new content since last briefing."
	 */
	Optional<Briefing> findLatestByTopicId(TopicId topicId);

	/**
	 * Full aggregate, scoped to the owning topic — {@code briefing} never
	 * distinguishes "not found" from "not owned by this topic" to a caller,
	 * same information-leak avoidance as {@code topic}'s ownership checks.
	 */
	Optional<Briefing> findByIdAndTopicId(BriefingId id, TopicId topicId);

	/**
	 * Lightweight projection (no ingested items) for the inline history
	 * list, newest first.
	 */
	List<BriefingSummary> findSummariesByTopicId(TopicId topicId);

}
