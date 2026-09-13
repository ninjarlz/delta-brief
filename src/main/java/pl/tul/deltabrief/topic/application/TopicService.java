package pl.tul.deltabrief.topic.application;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pl.tul.deltabrief.auth.domain.UserId;
import pl.tul.deltabrief.topic.application.port.out.CategoryRepository;
import pl.tul.deltabrief.topic.application.port.out.TopicRepository;
import pl.tul.deltabrief.topic.domain.CategoryId;
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
	 * @throws CategoryNotFoundException if {@code categoryId} doesn't exist —
	 *         only reachable via a tampered form value, since the UI picker is
	 *         DB-populated
	 * @throws DuplicateTopicNameException if the user already has a topic with
	 *         this name (case-insensitive)
	 * @throws TopicLimitReachedException if the user already has
	 *         {@value #MAX_TOPICS_PER_USER} topics
	 */
	public Topic createTopic(UserId userId, String name, CategoryId categoryId) {
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
		return topicRepository.save(Topic.create(userId, name, categoryId, Instant.now()));
	}

	public List<Topic> listTopics(UserId userId) {
		return topicRepository.findAllByUserId(userId);
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
