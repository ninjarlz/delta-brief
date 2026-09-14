package pl.tul.deltabrief.briefing.application;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.wiremock.spring.EnableWireMock;
import pl.tul.deltabrief.auth.application.port.out.UserRepository;
import pl.tul.deltabrief.auth.domain.User;
import pl.tul.deltabrief.auth.domain.UserId;
import pl.tul.deltabrief.config.SynchronousAsyncConfig;
import pl.tul.deltabrief.config.TestcontainersDatasourceConfig;
import pl.tul.deltabrief.shared.adapter.out.email.FakeEmailSender;
import pl.tul.deltabrief.topic.application.port.out.TopicRepository;
import pl.tul.deltabrief.topic.domain.CategoryId;
import pl.tul.deltabrief.topic.domain.Frequency;
import pl.tul.deltabrief.topic.domain.ScheduledRunStatus;
import pl.tul.deltabrief.topic.domain.Topic;

/**
 * Invokes {@link ScheduledBriefingRunner#runDueGenerations()} directly
 * rather than waiting for its real {@code @Scheduled} interval — no
 * {@code Clock} abstraction exists anywhere in this codebase (see
 * plan.md), so the due/not-due distinction is set up entirely via each
 * topic's seeded {@code nextDueAt}, matching {@link BriefingServiceTests}'
 * WireMock-stubbing conventions.
 *
 * <p><b>Deliberately not {@code @Transactional}</b> — unlike {@code
 * BriefingServiceTests}, the code under test ({@code runDueGenerations})
 * dispatches the actual generation work onto separate (virtual) worker
 * threads. A test-managed transaction is bound to the test method's own
 * thread only (Spring's {@code TransactionSynchronizationManager} is
 * {@code ThreadLocal}-based); a worker thread's own transaction can't see
 * the test thread's still-uncommitted setup rows, so generation would
 * always find "no such topic" and every assertion here would silently see
 * untouched state. Fixture rows are committed for real instead, then
 * cleaned up explicitly in {@link #cleanUpCommittedFixtures()}.
 */
@SpringBootTest(properties = {"app.async.email.enabled=false", "bucket4j.enabled=false", "spring.cache.type=none",
		"spring.ai.openai.base-url=${wiremock.server.baseUrl}", "app.google-news.base-url=${wiremock.server.baseUrl}"})
@Import({TestcontainersDatasourceConfig.class, SynchronousAsyncConfig.class})
@EnableWireMock
class ScheduledBriefingRunnerTests {

	private static final String VALID_RSS = """
			<?xml version="1.0" encoding="UTF-8"?>
			<rss version="2.0">
			  <channel>
			    <title>Test Feed</title>
			    <item>
			      <title>Test Headline</title>
			      <link>https://example.com/test-headline</link>
			      <pubDate>Tue, 02 Jan 2024 00:00:00 GMT</pubDate>
			    </item>
			  </channel>
			</rss>
			""";

	private static final String CHAT_COMPLETION_RESPONSE = """
			{
			  "id": "chatcmpl-test",
			  "object": "chat.completion",
			  "created": 1700000000,
			  "model": "gpt-4o-mini",
			  "choices": [
			    {
			      "index": 0,
			      "message": {
			        "role": "assistant",
			        "content": "{\\"keyChanges\\":\\"changes\\",\\"trendContinuation\\":\\"trend\\",\\"noiseSpeculation\\":\\"noise\\",\\"significance\\":\\"significance\\",\\"uncertainties\\":\\"uncertainties\\",\\"sourceImpact\\":\\"impact\\"}"
			      },
			      "finish_reason": "stop"
			    }
			  ],
			  "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20}
			}
			""";

	@Autowired
	private ScheduledBriefingRunner scheduledBriefingRunner;

	@Autowired
	private TopicRepository topicRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private FakeEmailSender fakeEmailSender;

	@Value("${wiremock.server.baseUrl}")
	private String wireMockBaseUrl;

	private final List<Long> createdCategoryIds = new ArrayList<>();
	private final List<Long> createdUserIds = new ArrayList<>();

	@BeforeEach
	void resetWireMockStubs() {
		WireMock.reset();
	}

	/**
	 * Deletes in FK-safe order: topics by category (their briefings cascade
	 * automatically), then the category's sources and the category itself,
	 * then the users created for this test — mirrors {@code
	 * BriefingServiceTests}' {@code @Transactional} cleanup intent, just done
	 * manually since that annotation isn't usable here (see class Javadoc).
	 */
	@AfterEach
	void cleanUpCommittedFixtures() {
		for (Long categoryId : createdCategoryIds) {
			jdbcTemplate.update("delete from topics where category_id = ?", categoryId);
			jdbcTemplate.update("delete from sources where category_id = ?", categoryId);
			jdbcTemplate.update("delete from categories where id = ?", categoryId);
		}
		for (Long userId : createdUserIds) {
			jdbcTemplate.update("delete from users where id = ?", userId);
		}
	}

	private void stubValidChatCompletion() {
		stubFor(post(urlEqualTo("/chat/completions")).willReturn(
				aResponse().withHeader("Content-Type", "application/json").withBody(CHAT_COMPLETION_RESPONSE)));
	}

	private UserId newUser() {
		String email = "scheduled-runner-" + UUID.randomUUID() + "@example.com";
		UserId userId = userRepository.save(User.register(email, "hashed-password", Instant.now())).id();
		createdUserIds.add(userId.value());
		return userId;
	}

	private CategoryId newCategoryWithFeedAt(String feedPath) {
		Long categoryId = jdbcTemplate.queryForObject("insert into categories(name) values (?) returning id",
				Long.class, "Test Category " + UUID.randomUUID());
		jdbcTemplate.update("insert into sources(category_id, name, feed_url) values (?, ?, ?)", categoryId,
				"Test Source", wireMockBaseUrl + feedPath);
		createdCategoryIds.add(categoryId);
		return new CategoryId(categoryId);
	}

	private Topic dueTopic(UserId owner, CategoryId categoryId, Instant nextDueAt) {
		Topic topic = topicRepository
				.save(Topic.create(owner, "Test Topic " + UUID.randomUUID(), categoryId, Instant.now()));
		topic.applySchedule(Frequency.DAILY, null, true, nextDueAt);
		return topicRepository.save(topic);
	}

	@Test
	void generatesADueTopicAndAdvancesItsNextDueAt() {
		String feedPath = "/feed-" + UUID.randomUUID() + ".xml";
		stubFor(get(urlEqualTo(feedPath)).willReturn(
				aResponse().withHeader("Content-Type", "application/rss+xml").withBody(VALID_RSS)));
		stubValidChatCompletion();
		UserId owner = newUser();
		Topic topic = dueTopic(owner, newCategoryWithFeedAt(feedPath), Instant.now().minus(Duration.ofMinutes(1)));

		scheduledBriefingRunner.runDueGenerations();

		Topic updated = topicRepository.findByIdAndUserId(topic.id(), owner).orElseThrow();
		assertThat(updated.nextDueAt()).isAfter(Instant.now());
		assertThat(updated.lastScheduledStatus()).isEqualTo(ScheduledRunStatus.SUCCESS);
	}

	@Test
	void leavesATopicNotYetDueUntouched() {
		UserId owner = newUser();
		// Truncated to microseconds — TIMESTAMPTZ's DB round-trip precision —
		// so the persisted and re-read value can be compared exactly below.
		Instant futureNextDueAt = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MICROS);
		Topic topic = dueTopic(owner, newCategoryWithFeedAt("/unused-" + UUID.randomUUID() + ".xml"), futureNextDueAt);

		scheduledBriefingRunner.runDueGenerations();

		Topic unchanged = topicRepository.findByIdAndUserId(topic.id(), owner).orElseThrow();
		assertThat(unchanged.nextDueAt()).isEqualTo(futureNextDueAt);
		assertThat(unchanged.lastScheduledStatus()).isNull();
	}

	@Test
	void recordsFailureAndLeavesNextDueAtUnchangedWhenGenerationFails() {
		stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withStatus(500)));
		UserId owner = newUser();
		Instant originalNextDueAt = Instant.now().minus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.MICROS);
		Topic topic = dueTopic(owner, newCategoryWithFeedAt("/unused-" + UUID.randomUUID() + ".xml"),
				originalNextDueAt);

		scheduledBriefingRunner.runDueGenerations();

		Topic updated = topicRepository.findByIdAndUserId(topic.id(), owner).orElseThrow();
		assertThat(updated.nextDueAt()).isEqualTo(originalNextDueAt);
		assertThat(updated.lastScheduledStatus()).isEqualTo(ScheduledRunStatus.FAILURE);
	}

	@Test
	void doesNothingWhenNoTopicIsDue() {
		scheduledBriefingRunner.runDueGenerations();
	}

	/**
	 * Proves the shared hook point (see {@code BriefingService.generateBriefing})
	 * actually covers the scheduled trigger too, not just the manual one —
	 * {@link BriefingServiceTests} already covers the manual path directly.
	 */
	@Test
	void sendsAnEmailForADueOptedInTopic() {
		String feedPath = "/feed-" + UUID.randomUUID() + ".xml";
		stubFor(get(urlEqualTo(feedPath)).willReturn(
				aResponse().withHeader("Content-Type", "application/rss+xml").withBody(VALID_RSS)));
		stubValidChatCompletion();
		UserId owner = newUser();
		String ownerEmail = userRepository.findEmailById(owner).orElseThrow();
		dueTopic(owner, newCategoryWithFeedAt(feedPath), Instant.now().minus(Duration.ofMinutes(1)));

		scheduledBriefingRunner.runDueGenerations();

		assertThat(fakeEmailSender.sentEmails()).anyMatch(sent -> sent.to().equals(ownerEmail));
	}

}
