package pl.tul.deltabrief.topic.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pl.tul.deltabrief.auth.domain.UserId;
import pl.tul.deltabrief.topic.application.port.out.CategoryRepository;
import pl.tul.deltabrief.topic.application.port.out.TopicRepository;
import pl.tul.deltabrief.topic.domain.CategoryId;
import pl.tul.deltabrief.topic.domain.Frequency;
import pl.tul.deltabrief.topic.domain.ScheduleCalculator;
import pl.tul.deltabrief.topic.domain.Topic;
import pl.tul.deltabrief.topic.domain.TopicId;

/**
 * Orchestrates topic creation, listing, and deletion — enforcing per-user
 * name uniqueness and a per-user topic cap on top of the DB-level
 * guarantees {@link TopicRepository}'s adapter already provides.
 */
@Service
@RequiredArgsConstructor
public class TopicService {

	private static final int MAX_TOPICS_PER_USER = 20;

	private final TopicRepository topicRepository;
	private final CategoryRepository categoryRepository;

	/**
	 * Convenience overload for the common case of no description.
	 */
	public Topic createTopic(UserId userId, String name, CategoryId categoryId) {
		return createTopic(userId, name, categoryId, null);
	}

	/**
	 * @param description the user's optional observation-goal note (FR-004),
	 *         fed into future briefing generation prompts for this topic —
	 *         {@code null} if not provided.
	 * @throws CategoryNotFoundException if {@code categoryId} doesn't exist —
	 *         only reachable via a tampered form value, since the UI picker is
	 *         DB-populated
	 * @throws DuplicateTopicNameException if the user already has a topic with
	 *         this name (case-insensitive)
	 * @throws TopicLimitReachedException if the user already has
	 *         {@value #MAX_TOPICS_PER_USER} topics
	 */
	public Topic createTopic(UserId userId, String name, CategoryId categoryId, String description) {
		return createTopic(userId, name, categoryId, description, Frequency.DAILY, null);
	}

	/**
	 * @param frequency how often DeltaBrief should regenerate this topic's
	 * briefing automatically (FR-008); {@link Frequency#DAILY} is the
	 * product default.
	 * @param preferredHour optional UTC hour-of-day (0-23) to nudge
	 * scheduled generation toward — {@code null} for no preference.
	 * @throws CategoryNotFoundException if {@code categoryId} doesn't exist —
	 *         only reachable via a tampered form value, since the UI picker is
	 *         DB-populated
	 * @throws DuplicateTopicNameException if the user already has a topic with
	 *         this name (case-insensitive)
	 * @throws TopicLimitReachedException if the user already has
	 *         {@value #MAX_TOPICS_PER_USER} topics
	 */
	public Topic createTopic(UserId userId, String name, CategoryId categoryId, String description,
			Frequency frequency, Integer preferredHour) {
		if (!categoryRepository.existsById(categoryId)) {
			throw new CategoryNotFoundException(categoryId);
		}
		if (topicRepository.existsByUserIdAndNameIgnoreCase(userId, name)) {
			throw new DuplicateTopicNameException(name);
		}
		// Check-then-act, not DB-enforced like the uniqueness check above — a
		// deliberate, accepted tradeoff. Two concurrent requests from the same
		// user could race past this and land slightly over the cap; unlike the
		// duplicate-name case, this is a soft UX guardrail against accidental
		// topic-spam, not a security or data-integrity boundary, so the small
		// window isn't worth a locking/trigger-based fix at this project's scale.
		if (topicRepository.countByUserId(userId) >= MAX_TOPICS_PER_USER) {
			throw new TopicLimitReachedException(userId);
		}
		Instant createdAt = Instant.now();
		Topic topic = Topic.create(userId, name, categoryId, description, createdAt);
		// Placeholder anchor — overwritten with the real generatedAt anchor
		// the first time this topic actually generates a briefing (onboarding,
		// manual, or scheduled all advance the schedule the same way; see
		// BriefingService).
		topic.applySchedule(frequency, preferredHour, ScheduleCalculator.nextDueAt(createdAt, frequency, preferredHour));
		return topicRepository.save(topic);
	}

	public List<Topic> listTopics(UserId userId) {
		return topicRepository.findAllByUserId(userId);
	}

	/**
	 * Owner-scoped full topic lookup for the schedule edit flow, which needs
	 * the current frequency/preferred hour to pre-populate the edit form —
	 * {@code null}-safe empty result for an unknown/unowned ID.
	 */
	public Optional<Topic> findForSchedule(UserId userId, TopicId topicId) {
		return topicRepository.findByIdAndUserId(topicId, userId);
	}

	/**
	 * Silently no-ops for a topic ID that doesn't exist or isn't owned by
	 * {@code userId}, mirroring {@link #deleteTopic}'s no-information-leak
	 * contract. {@code nextDueAt} is recomputed anchored at "now" — editing
	 * the schedule is itself a reset, the same way a manual/scheduled
	 * generation resets it (see {@code BriefingService}).
	 */
	public void updateSchedule(UserId userId, TopicId topicId, Frequency frequency, Integer preferredHour) {
		topicRepository.findByIdAndUserId(topicId, userId).ifPresent(topic -> {
			Instant nextDueAt = ScheduleCalculator.nextDueAt(Instant.now(), frequency, preferredHour);
			topic.applySchedule(frequency, preferredHour, nextDueAt);
			topicRepository.save(topic);
		});
	}

	/**
	 * Silently no-ops for a topic ID that doesn't exist or isn't owned by
	 * {@code userId} — the caller always shows the same outcome regardless
	 * (see {@link TopicRepository#deleteByIdAndUserId}'s contract), so this
	 * can't be used to probe for another user's topic IDs.
	 */
	public void deleteTopic(UserId userId, TopicId topicId) {
		topicRepository.deleteByIdAndUserId(topicId, userId);
	}

	public static class CategoryNotFoundException extends RuntimeException {
		public CategoryNotFoundException(CategoryId categoryId) {
			super("No such category: " + categoryId.value());
		}
	}

	public static class DuplicateTopicNameException extends RuntimeException {
		public DuplicateTopicNameException(String name) {
			super("Topic name already in use: " + name);
		}
	}

	public static class TopicLimitReachedException extends RuntimeException {
		public TopicLimitReachedException(UserId userId) {
			super("Topic limit reached for user: " + userId.value());
		}
	}

}
