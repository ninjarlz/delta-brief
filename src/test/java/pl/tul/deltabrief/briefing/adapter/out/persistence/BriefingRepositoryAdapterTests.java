package pl.tul.deltabrief.briefing.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import pl.tul.deltabrief.auth.application.port.out.UserRepository;
import pl.tul.deltabrief.auth.domain.User;
import pl.tul.deltabrief.auth.domain.UserId;
import pl.tul.deltabrief.briefing.application.port.out.BriefingRepository;
import pl.tul.deltabrief.briefing.application.port.out.BriefingSummary;
import pl.tul.deltabrief.briefing.domain.Briefing;
import pl.tul.deltabrief.briefing.domain.BriefingId;
import pl.tul.deltabrief.briefing.domain.BriefingType;
import pl.tul.deltabrief.briefing.domain.IngestedItem;
import pl.tul.deltabrief.config.SynchronousAsyncConfig;
import pl.tul.deltabrief.config.TestcontainersDatasourceConfig;
import pl.tul.deltabrief.topic.application.port.out.CategoryRepository;
import pl.tul.deltabrief.topic.application.port.out.TopicRepository;
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
class BriefingRepositoryAdapterTests {

	@Autowired
	private BriefingRepository briefingRepository;

	@Autowired
	private TopicRepository topicRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	private static String uniqueEmail() {
		return "briefing-owner-" + UUID.randomUUID() + "@example.com";
	}

	private TopicId newTopic() {
		return newTopicOwnedBy(newUser());
	}

	private UserId newUser() {
		return userRepository.save(User.register(uniqueEmail(), "hashed-password", Instant.now())).id();
	}

	private TopicId newTopicOwnedBy(UserId owner) {
		CategoryId categoryId = categoryRepository.findAll().get(0).id();
		Topic topic = topicRepository.save(Topic.create(owner, "War in Ukraine", categoryId, Instant.now()));
		return topic.id();
	}

	private static Briefing newBriefing(TopicId topicId, BriefingType type, Instant generatedAt) {
		IngestedItem item = new IngestedItem("BBC News", "Headline", "https://example.com/1", generatedAt,
				generatedAt);
		return Briefing.generate(topicId, type, generatedAt, "key changes", "trend continuation", "noise",
				"significance", "uncertainties", "source impact", List.of(item));
	}

	@Test
	void savesAndFindsLatestByTopicIdIncludingIngestedItems() {
		TopicId topicId = newTopic();

		Briefing saved = briefingRepository.save(newBriefing(topicId, BriefingType.ONBOARDING, Instant.now()));

		assertThat(saved.id()).isNotNull();
		Briefing found = briefingRepository.findLatestByTopicId(topicId).orElseThrow();
		assertThat(found.type()).isEqualTo(BriefingType.ONBOARDING);
		assertThat(found.keyChanges()).isEqualTo("key changes");
		assertThat(found.ingestedItems()).hasSize(1);
		assertThat(found.ingestedItems().get(0).sourceName()).isEqualTo("BBC News");
		assertThat(found.ingestedItems().get(0).link()).isEqualTo("https://example.com/1");
	}

	@Test
	void findLatestByTopicIdReturnsTheMostRecentOne() {
		TopicId topicId = newTopic();
		Instant earlier = Instant.now().minusSeconds(3600);
		Instant later = Instant.now();
		briefingRepository.save(newBriefing(topicId, BriefingType.ONBOARDING, earlier));
		Briefing latest = briefingRepository.save(newBriefing(topicId, BriefingType.DELTA, later));

		Briefing found = briefingRepository.findLatestByTopicId(topicId).orElseThrow();

		assertThat(found.id()).isEqualTo(latest.id());
		assertThat(found.type()).isEqualTo(BriefingType.DELTA);
	}

	@Test
	void findLatestByTopicIdIsEmptyWhenNoneExist() {
		TopicId topicId = newTopic();

		assertThat(briefingRepository.findLatestByTopicId(topicId)).isEmpty();
	}

	@Test
	void findByIdAndTopicIdIsEmptyForAnotherTopic() {
		TopicId topicId = newTopic();
		TopicId otherTopicId = newTopic();
		Briefing saved = briefingRepository.save(newBriefing(topicId, BriefingType.ONBOARDING, Instant.now()));

		assertThat(briefingRepository.findByIdAndTopicId(saved.id(), otherTopicId)).isEmpty();
	}

	@Test
	void findByIdAndTopicIdIsEmptyForAnUnknownId() {
		TopicId topicId = newTopic();

		assertThat(briefingRepository.findByIdAndTopicId(new BriefingId(999_999L), topicId)).isEmpty();
	}

	@Test
	void findSummariesByTopicIdReturnsNewestFirstWithoutHydratingItems() {
		TopicId topicId = newTopic();
		Instant earlier = Instant.now().minusSeconds(3600);
		Instant later = Instant.now();
		briefingRepository.save(newBriefing(topicId, BriefingType.ONBOARDING, earlier));
		briefingRepository.save(newBriefing(topicId, BriefingType.DELTA, later));

		List<BriefingSummary> summaries = briefingRepository.findSummariesByTopicId(topicId);

		assertThat(summaries).hasSize(2);
		assertThat(summaries.get(0).type()).isEqualTo(BriefingType.DELTA);
		assertThat(summaries.get(1).type()).isEqualTo(BriefingType.ONBOARDING);
	}

	/**
	 * {@code briefings.topic_id} is {@code ON DELETE CASCADE} (V8 migration)
	 * specifically so topic deletion — an already-shipped feature
	 * ({@code TopicController.deleteTopic}) — keeps working once a topic has
	 * briefings, rather than failing on this FK. That intent was previously
	 * untested; this proves it.
	 */
	@Test
	void deletingATopicCascadesToDeleteItsBriefings() {
		UserId owner = newUser();
		TopicId topicId = newTopicOwnedBy(owner);
		briefingRepository.save(newBriefing(topicId, BriefingType.ONBOARDING, Instant.now()));
		briefingRepository.save(newBriefing(topicId, BriefingType.DELTA, Instant.now()));

		boolean deleted = topicRepository.deleteByIdAndUserId(topicId, owner);

		assertThat(deleted).isTrue();
		assertThat(briefingRepository.findLatestByTopicId(topicId)).isEmpty();
		assertThat(briefingRepository.findSummariesByTopicId(topicId)).isEmpty();
	}

}
