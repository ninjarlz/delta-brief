package pl.tul.deltabrief.topic.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import pl.tul.deltabrief.auth.application.port.out.UserRepository;
import pl.tul.deltabrief.auth.domain.User;
import pl.tul.deltabrief.auth.domain.UserId;
import pl.tul.deltabrief.config.SynchronousAsyncConfig;
import pl.tul.deltabrief.config.TestcontainersDatasourceConfig;
import pl.tul.deltabrief.topic.application.TopicService.CategoryNotFoundException;
import pl.tul.deltabrief.topic.application.TopicService.DuplicateTopicNameException;
import pl.tul.deltabrief.topic.application.TopicService.TopicLimitReachedException;
import pl.tul.deltabrief.topic.application.port.out.CategoryRepository;
import pl.tul.deltabrief.topic.domain.CategoryId;
import pl.tul.deltabrief.topic.domain.Frequency;
import pl.tul.deltabrief.topic.domain.Topic;
import pl.tul.deltabrief.topic.domain.TopicId;

/**
 * Reuses the exact same context shape as {@code DeltaBriefApplicationTests}
 * (same {@code @Import}) so Spring's test context cache shares one
 * Testcontainers-backed datasource — see {@link TestcontainersDatasourceConfig}'s
 * single-fixed-local-port constraint. No mocks/fakes for {@code
 * TopicRepository}/{@code CategoryRepository} — exercises the real
 * repositories/DB, mirroring {@code RegistrationServiceTests}'s convention.
 */
@SpringBootTest(properties = "app.async.email.enabled=false")
@Import({TestcontainersDatasourceConfig.class, SynchronousAsyncConfig.class})
class TopicServiceTests {

	@Autowired
	private TopicService topicService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	private static String uniqueEmail() {
		return "topic-svc-" + UUID.randomUUID() + "@example.com";
	}

	private UserId newUser() {
		return userRepository.save(User.register(uniqueEmail(), "hashed-password", Instant.now())).id();
	}

	private CategoryId anyCategoryId() {
		return categoryRepository.findAll().get(0).id();
	}

	@Test
	void createsATopic() {
		UserId userId = newUser();
		CategoryId categoryId = anyCategoryId();

		Topic created = topicService.createTopic(userId, "War in Ukraine", categoryId);

		assertThat(created.id()).isNotNull();
		assertThat(topicService.listTopics(userId)).extracting(Topic::name).containsExactly("War in Ukraine");
	}

	@Test
	void createsATopicWithTheDailyDefaultFrequencyAndAComputedNextDueAt() {
		UserId userId = newUser();

		Topic created = topicService.createTopic(userId, "War in Ukraine", anyCategoryId());

		assertThat(created.frequency()).isEqualTo(Frequency.DAILY);
		assertThat(created.preferredHour()).isNull();
		assertThat(created.nextDueAt()).isNotNull();
	}

	@Test
	void createsATopicWithAnExplicitFrequencyAndPreferredHour() {
		UserId userId = newUser();

		Topic created = topicService.createTopic(userId, "War in Ukraine", anyCategoryId(), "note", Frequency.WEEKLY, 9);

		assertThat(created.frequency()).isEqualTo(Frequency.WEEKLY);
		assertThat(created.preferredHour()).isEqualTo(9);
		assertThat(created.nextDueAt()).isNotNull();
	}

	@Test
	void createsAManualTopicWithNoNextDueAt() {
		UserId userId = newUser();

		Topic created = topicService.createTopic(userId, "War in Ukraine", anyCategoryId(), null, Frequency.MANUAL, null);

		assertThat(created.nextDueAt()).isNull();
	}

	@Test
	void rejectsAnUnknownCategory() {
		UserId userId = newUser();

		assertThatExceptionOfType(CategoryNotFoundException.class)
				.isThrownBy(() -> topicService.createTopic(userId, "War in Ukraine", new CategoryId(999_999L)));
	}

	@Test
	void rejectsACaseInsensitiveDuplicateNameForTheSameUser() {
		UserId userId = newUser();
		CategoryId categoryId = anyCategoryId();
		topicService.createTopic(userId, "War in Ukraine", categoryId);

		assertThatExceptionOfType(DuplicateTopicNameException.class)
				.isThrownBy(() -> topicService.createTopic(userId, "WAR IN UKRAINE", categoryId));
	}

	@Test
	void rejectsThe21stTopicForTheSameUser() {
		UserId userId = newUser();
		CategoryId categoryId = anyCategoryId();
		for (int i = 0; i < 20; i++) {
			topicService.createTopic(userId, "Topic " + i, categoryId);
		}

		assertThatExceptionOfType(TopicLimitReachedException.class)
				.isThrownBy(() -> topicService.createTopic(userId, "One too many", categoryId));
	}

	@Test
	void listTopicsNeverReturnsAnotherUsersTopics() {
		UserId userA = newUser();
		UserId userB = newUser();
		CategoryId categoryId = anyCategoryId();
		topicService.createTopic(userA, "User A's topic", categoryId);
		topicService.createTopic(userB, "User B's topic", categoryId);

		assertThat(topicService.listTopics(userA)).extracting(Topic::name).containsExactly("User A's topic");
	}

	@Test
	void deleteTopicIsANoOpForATopicOwnedByAnotherUser() {
		UserId owner = newUser();
		UserId otherUser = newUser();
		Topic topic = topicService.createTopic(owner, "War in Ukraine", anyCategoryId());

		topicService.deleteTopic(otherUser, topic.id());

		assertThat(topicService.listTopics(owner)).extracting(Topic::name).containsExactly("War in Ukraine");
	}

	@Test
	void deleteTopicIsANoOpForAnUnknownId() {
		UserId userId = newUser();

		topicService.deleteTopic(userId, new TopicId(999_999L));

		assertThat(topicService.listTopics(userId)).isEmpty();
	}

	@Test
	void deleteTopicRemovesTheOwnersTopic() {
		UserId owner = newUser();
		Topic topic = topicService.createTopic(owner, "War in Ukraine", anyCategoryId());

		topicService.deleteTopic(owner, topic.id());

		assertThat(topicService.listTopics(owner)).isEmpty();
	}

	@Test
	void updateScheduleChangesFrequencyPreferredHourAndRecomputesNextDueAt() {
		UserId owner = newUser();
		Topic topic = topicService.createTopic(owner, "War in Ukraine", anyCategoryId());

		topicService.updateSchedule(owner, topic.id(), Frequency.WEEKLY, 9);

		Topic updated = topicService.findForSchedule(owner, topic.id()).orElseThrow();
		assertThat(updated.frequency()).isEqualTo(Frequency.WEEKLY);
		assertThat(updated.preferredHour()).isEqualTo(9);
		assertThat(updated.nextDueAt()).isNotNull();
	}

	@Test
	void updateScheduleToManualClearsNextDueAt() {
		UserId owner = newUser();
		Topic topic = topicService.createTopic(owner, "War in Ukraine", anyCategoryId());

		topicService.updateSchedule(owner, topic.id(), Frequency.MANUAL, null);

		assertThat(topicService.findForSchedule(owner, topic.id()).orElseThrow().nextDueAt()).isNull();
	}

	@Test
	void updateScheduleIsANoOpForATopicOwnedByAnotherUser() {
		UserId owner = newUser();
		UserId otherUser = newUser();
		Topic topic = topicService.createTopic(owner, "War in Ukraine", anyCategoryId());

		topicService.updateSchedule(otherUser, topic.id(), Frequency.WEEKLY, 9);

		assertThat(topicService.findForSchedule(owner, topic.id()).orElseThrow().frequency()).isEqualTo(Frequency.DAILY);
	}

	@Test
	void updateScheduleIsANoOpForAnUnknownId() {
		UserId owner = newUser();

		topicService.updateSchedule(owner, new TopicId(999_999L), Frequency.WEEKLY, 9);

		assertThat(topicService.listTopics(owner)).isEmpty();
	}

	@Test
	void findForScheduleIsEmptyForAnotherUsersTopic() {
		UserId owner = newUser();
		UserId otherUser = newUser();
		Topic topic = topicService.createTopic(owner, "War in Ukraine", anyCategoryId());

		assertThat(topicService.findForSchedule(otherUser, topic.id())).isEmpty();
	}

}
