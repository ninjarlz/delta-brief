package pl.tul.deltabrief.topic.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import pl.tul.deltabrief.auth.application.port.out.UserRepository;
import pl.tul.deltabrief.auth.domain.User;
import pl.tul.deltabrief.auth.domain.UserId;
import pl.tul.deltabrief.config.SynchronousAsyncConfig;
import pl.tul.deltabrief.config.TestcontainersDatasourceConfig;
import pl.tul.deltabrief.topic.application.port.out.CategoryRepository;
import pl.tul.deltabrief.topic.application.port.out.TopicRepository;
import pl.tul.deltabrief.topic.application.port.out.TopicSummary;
import pl.tul.deltabrief.topic.domain.CategoryId;
import pl.tul.deltabrief.topic.domain.Topic;
import pl.tul.deltabrief.topic.domain.TopicId;

/**
 * Reuses the exact same context shape as {@code DeltaBriefApplicationTests}
 * (same {@code @Import}) so Spring's test context cache shares one
 * Testcontainers-backed datasource — see {@link TestcontainersDatasourceConfig}'s
 * single-fixed-local-port constraint.
 */
@SpringBootTest(properties = "app.async.email.enabled=false")
@Import({TestcontainersDatasourceConfig.class, SynchronousAsyncConfig.class})
class TopicRepositoryAdapterTests {

	@Autowired
	private TopicRepository topicRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	private static String uniqueEmail() {
		return "topic-owner-" + UUID.randomUUID() + "@example.com";
	}

	private UserId newUser() {
		User user = userRepository.save(User.register(uniqueEmail(), "hashed-password", Instant.now()));
		return user.id();
	}

	private CategoryId anyCategoryId() {
		return categoryRepository.findAll().get(0).id();
	}

	@Test
	void savesAndFindsByUser() {
		UserId owner = newUser();
		CategoryId categoryId = anyCategoryId();

		Topic saved = topicRepository.save(Topic.create(owner, "War in Ukraine", categoryId, Instant.now()));

		assertThat(saved.id()).isNotNull();
		List<Topic> found = topicRepository.findAllByUserId(owner);
		assertThat(found).extracting(Topic::name).containsExactly("War in Ukraine");
	}

	@Test
	void findAllByUserIdExcludesAnotherUsersTopics() {
		UserId userA = newUser();
		UserId userB = newUser();
		CategoryId categoryId = anyCategoryId();
		topicRepository.save(Topic.create(userA, "User A's topic", categoryId, Instant.now()));
		topicRepository.save(Topic.create(userB, "User B's topic", categoryId, Instant.now()));

		List<Topic> found = topicRepository.findAllByUserId(userA);

		assertThat(found).extracting(Topic::name).containsExactly("User A's topic");
	}

	@Test
	void rejectsCaseVariantDuplicateNameForTheSameUser() {
		UserId owner = newUser();
		CategoryId categoryId = anyCategoryId();
		topicRepository.save(Topic.create(owner, "War in Ukraine", categoryId, Instant.now()));

		assertThatException()
				.isThrownBy(() -> topicRepository.save(Topic.create(owner, "WAR IN UKRAINE", categoryId, Instant.now())))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void sameNameIsAllowedAcrossDifferentUsers() {
		UserId userA = newUser();
		UserId userB = newUser();
		CategoryId categoryId = anyCategoryId();

		topicRepository.save(Topic.create(userA, "War in Ukraine", categoryId, Instant.now()));
		Topic saved = topicRepository.save(Topic.create(userB, "War in Ukraine", categoryId, Instant.now()));

		assertThat(saved.id()).isNotNull();
	}

	@Test
	void existsByUserIdAndNameIgnoreCaseIsCaseInsensitive() {
		UserId owner = newUser();
		topicRepository.save(Topic.create(owner, "War in Ukraine", anyCategoryId(), Instant.now()));

		assertThat(topicRepository.existsByUserIdAndNameIgnoreCase(owner, "war in ukraine")).isTrue();
		assertThat(topicRepository.existsByUserIdAndNameIgnoreCase(owner, "Something else")).isFalse();
	}

	@Test
	void deleteByIdAndUserIdReturnsFalseForATopicOwnedByAnotherUser() {
		UserId owner = newUser();
		UserId otherUser = newUser();
		Topic topic = topicRepository.save(Topic.create(owner, "War in Ukraine", anyCategoryId(), Instant.now()));

		boolean deleted = topicRepository.deleteByIdAndUserId(topic.id(), otherUser);

		assertThat(deleted).isFalse();
		assertThat(topicRepository.findAllByUserId(owner)).extracting(Topic::name).containsExactly("War in Ukraine");
	}

	@Test
	void deleteByIdAndUserIdReturnsFalseForAnUnknownId() {
		UserId owner = newUser();

		assertThat(topicRepository.deleteByIdAndUserId(new TopicId(999_999L), owner)).isFalse();
	}

	@Test
	void deleteByIdAndUserIdRemovesTheOwnersTopic() {
		UserId owner = newUser();
		Topic topic = topicRepository.save(Topic.create(owner, "War in Ukraine", anyCategoryId(), Instant.now()));

		boolean deleted = topicRepository.deleteByIdAndUserId(topic.id(), owner);

		assertThat(deleted).isTrue();
		assertThat(topicRepository.findAllByUserId(owner)).isEmpty();
	}

	@Test
	void findSummaryByIdAndUserIdReturnsNameAndCategoryForTheOwner() {
		UserId owner = newUser();
		CategoryId categoryId = anyCategoryId();
		Topic topic = topicRepository.save(Topic.create(owner, "War in Ukraine", categoryId, Instant.now()));

		assertThat(topicRepository.findSummaryByIdAndUserId(topic.id(), owner))
				.contains(new TopicSummary("War in Ukraine", categoryId, null));
	}

	@Test
	void findSummaryByIdAndUserIdIncludesTheDescriptionWhenSet() {
		UserId owner = newUser();
		CategoryId categoryId = anyCategoryId();
		Topic topic = topicRepository.save(
				Topic.create(owner, "War in Ukraine", categoryId, "Tracking the humanitarian angle", Instant.now()));

		assertThat(topicRepository.findSummaryByIdAndUserId(topic.id(), owner))
				.contains(new TopicSummary("War in Ukraine", categoryId, "Tracking the humanitarian angle"));
	}

	@Test
	void findSummaryByIdAndUserIdIsEmptyForAnotherUsersTopic() {
		UserId owner = newUser();
		UserId otherUser = newUser();
		Topic topic = topicRepository.save(Topic.create(owner, "War in Ukraine", anyCategoryId(), Instant.now()));

		assertThat(topicRepository.findSummaryByIdAndUserId(topic.id(), otherUser)).isEmpty();
	}

	@Test
	void findSummaryByIdAndUserIdIsEmptyForAnUnknownId() {
		UserId owner = newUser();

		assertThat(topicRepository.findSummaryByIdAndUserId(new TopicId(999_999L), owner)).isEmpty();
	}

	@Test
	void findByIdAndUserIdReturnsTheFullTopicForTheOwner() {
		UserId owner = newUser();
		CategoryId categoryId = anyCategoryId();
		Topic topic = topicRepository.save(Topic.create(owner, "War in Ukraine", categoryId, Instant.now()));

		Topic found = topicRepository.findByIdAndUserId(topic.id(), owner).orElseThrow();

		assertThat(found.id()).isEqualTo(topic.id());
		assertThat(found.name()).isEqualTo("War in Ukraine");
		assertThat(found.categoryId()).isEqualTo(categoryId);
	}

	@Test
	void findByIdAndUserIdIsEmptyForAnotherUsersTopic() {
		UserId owner = newUser();
		UserId otherUser = newUser();
		Topic topic = topicRepository.save(Topic.create(owner, "War in Ukraine", anyCategoryId(), Instant.now()));

		assertThat(topicRepository.findByIdAndUserId(topic.id(), otherUser)).isEmpty();
	}

}
