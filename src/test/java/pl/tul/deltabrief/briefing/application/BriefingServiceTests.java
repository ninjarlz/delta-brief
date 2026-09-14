package pl.tul.deltabrief.briefing.application;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.github.tomakehurst.wiremock.client.WireMock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.wiremock.spring.EnableWireMock;
import pl.tul.deltabrief.auth.application.port.out.UserRepository;
import pl.tul.deltabrief.auth.domain.User;
import pl.tul.deltabrief.auth.domain.UserId;
import pl.tul.deltabrief.briefing.application.port.out.BriefingContentGenerator.GenerationFailedException;
import pl.tul.deltabrief.briefing.domain.Briefing;
import pl.tul.deltabrief.briefing.domain.BriefingType;
import pl.tul.deltabrief.briefing.domain.IngestedItem;
import pl.tul.deltabrief.config.SynchronousAsyncConfig;
import pl.tul.deltabrief.config.TestcontainersDatasourceConfig;
import pl.tul.deltabrief.shared.adapter.out.email.FakeEmailSender;
import pl.tul.deltabrief.topic.application.port.out.TopicRepository;
import pl.tul.deltabrief.topic.domain.CategoryId;
import pl.tul.deltabrief.topic.domain.Frequency;
import pl.tul.deltabrief.topic.domain.ScheduleCalculator;
import pl.tul.deltabrief.topic.domain.Topic;
import pl.tul.deltabrief.topic.domain.TopicId;

/**
 * Full orchestration, with both external calls (RSS fetch, OpenAI) stubbed
 * via WireMock — no automated test makes a real network call (see plan.md's
 * WireMock stubbing points). {@code bucket4j.enabled=false} +
 * {@code spring.cache.type=none} avoid a JCache/Caffeine CacheManager
 * collision this test's distinct Spring context would otherwise hit (see
 * {@link TestcontainersDatasourceConfig#localDataSource()}'s reuse fix,
 * added alongside this test for exactly this scenario).
 *
 * <p>{@code categories}/{@code sources} are reference data with no
 * application-level write path (by design — see {@code Category}'s
 * Javadoc), so test fixtures are seeded directly via {@link JdbcTemplate}
 * rather than through a repository. {@code @Transactional} rolls every
 * inserted row back at the end of each test — without it, these inserts
 * would permanently pollute the shared V6-seeded {@code categories}/{@code
 * sources} tables that other test classes assert exact contents against
 * (this test's distinct Spring context, forced by {@code @EnableWireMock},
 * now safely reuses the same physical database other contexts use — see
 * {@link TestcontainersDatasourceConfig#localDataSource()}'s reuse fix).
 */
@SpringBootTest(properties = {"app.async.email.enabled=false", "bucket4j.enabled=false", "spring.cache.type=none",
		"spring.ai.openai.base-url=${wiremock.server.baseUrl}", "app.google-news.base-url=${wiremock.server.baseUrl}"})
@Import({TestcontainersDatasourceConfig.class, SynchronousAsyncConfig.class})
@EnableWireMock
@Transactional
class BriefingServiceTests {

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
	private BriefingService briefingService;

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

	@BeforeEach
	void resetWireMockStubs() {
		WireMock.reset();
	}

	private void stubValidChatCompletion() {
		stubFor(post(urlEqualTo("/chat/completions")).willReturn(
				aResponse().withHeader("Content-Type", "application/json").withBody(CHAT_COMPLETION_RESPONSE)));
	}

	private UserId newUser() {
		String email = "briefing-service-" + UUID.randomUUID() + "@example.com";
		return userRepository.save(User.register(email, "hashed-password", Instant.now())).id();
	}

	/**
	 * Seeds a brand-new category with one source pointing at WireMock's own
	 * base URL, at a unique path (so stubs never collide across tests).
	 */
	private CategoryId newCategoryWithFeedAt(String feedPath) {
		Long categoryId = jdbcTemplate.queryForObject("insert into categories(name) values (?) returning id",
				Long.class, "Test Category " + UUID.randomUUID());
		jdbcTemplate.update("insert into sources(category_id, name, feed_url) values (?, ?, ?)", categoryId,
				"Test Source", wireMockBaseUrl + feedPath);
		return new CategoryId(categoryId);
	}

	private TopicId newTopic(UserId owner, CategoryId categoryId) {
		Topic topic = topicRepository
				.save(Topic.create(owner, "Test Topic " + UUID.randomUUID(), categoryId, Instant.now()));
		return topic.id();
	}

	private TopicId newTopic(UserId owner, CategoryId categoryId, boolean emailEnabled) {
		Topic topic = Topic.create(owner, "Test Topic " + UUID.randomUUID(), categoryId, Instant.now());
		topic.applySchedule(topic.frequency(), topic.preferredTime(), emailEnabled, topic.nextDueAt());
		return topicRepository.save(topic).id();
	}

	private TopicId newTopic(UserId owner, CategoryId categoryId, boolean emailEnabled, Frequency frequency) {
		Topic topic = Topic.create(owner, "Test Topic " + UUID.randomUUID(), categoryId, Instant.now());
		topic.applySchedule(frequency, topic.preferredTime(), emailEnabled, topic.nextDueAt());
		return topicRepository.save(topic).id();
	}

	@Test
	void firstGenerationForATopicProducesAnOnboardingBriefing() {
		String feedPath = "/feed-" + UUID.randomUUID() + ".xml";
		stubFor(get(urlEqualTo(feedPath)).willReturn(
				aResponse().withHeader("Content-Type", "application/rss+xml").withBody(VALID_RSS)));
		stubValidChatCompletion();
		UserId owner = newUser();
		TopicId topicId = newTopic(owner, newCategoryWithFeedAt(feedPath));

		Briefing briefing = briefingService.generateBriefing(topicId, owner);

		assertThat(briefing.id()).isNotNull();
		assertThat(briefing.type()).isEqualTo(BriefingType.ONBOARDING);
		assertThat(briefing.keyChanges()).isEqualTo("changes");
		assertThat(briefing.ingestedItems()).hasSize(1);
		assertThat(briefing.ingestedItems().get(0).title()).isEqualTo("Test Headline");
	}

	/**
	 * The other tests in this class leave the Google News search feed
	 * unstubbed on purpose — WireMock's default 404 for an unmatched request
	 * is treated as {@code SourceUnavailableException} the same as any other
	 * unreachable source (see {@code aFailingSourceDoesNotBlockGeneration}),
	 * so it simply contributes nothing rather than breaking those tests' item
	 * counts. This test is the one place that actually stubs it, to verify
	 * it's queried with the topic's name and its items really do get
	 * ingested alongside the category feed's.
	 */
	@Test
	void alsoIngestsFromTheTopicTargetedGoogleNewsSearchFeed() {
		String categoryFeedPath = "/feed-" + UUID.randomUUID() + ".xml";
		stubFor(get(urlEqualTo(categoryFeedPath)).willReturn(
				aResponse().withHeader("Content-Type", "application/rss+xml").withBody(VALID_RSS)));
		stubValidChatCompletion();
		UserId owner = newUser();
		CategoryId categoryId = newCategoryWithFeedAt(categoryFeedPath);
		Topic topic = topicRepository
				.save(Topic.create(owner, "Test Topic " + UUID.randomUUID(), categoryId, Instant.now()));
		String googleNewsRss = """
				<?xml version="1.0" encoding="UTF-8"?>
				<rss version="2.0">
				  <channel>
				    <title>Google News</title>
				    <item>
				      <title>Targeted Headline</title>
				      <link>https://example.com/targeted-headline</link>
				      <pubDate>Tue, 02 Jan 2024 00:00:00 GMT</pubDate>
				    </item>
				  </channel>
				</rss>
				""";
		stubFor(get(urlPathEqualTo("/rss/search")).withQueryParam("q", equalTo(topic.name())).willReturn(
				aResponse().withHeader("Content-Type", "application/rss+xml").withBody(googleNewsRss)));

		Briefing briefing = briefingService.generateBriefing(topic.id(), owner);

		assertThat(briefing.ingestedItems()).extracting(IngestedItem::title)
				.containsExactlyInAnyOrder("Test Headline", "Targeted Headline");
		assertThat(briefing.ingestedItems()).extracting(IngestedItem::sourceName)
				.contains("Google News: " + topic.name());
	}

	/**
	 * Relevance judgment for what to actually cite is left entirely to the
	 * LLM (see {@code BriefingPromptBuilder.SOURCE_RELEVANCE_AND_PRIORITY_GUIDANCE})
	 * rather than pre-filtered during ingestion — a local keyword filter was
	 * tried and removed (unreliable, missed paraphrases like "Kyiv" for a
	 * "Ukraine" topic). This seeds a category feed with an on-topic and an
	 * off-topic-looking item, and a search feed with an off-topic-looking
	 * item, to prove every fetched item from every concurrently-fetched
	 * source is ingested — none of them dropped locally — regardless of how
	 * relevant its title looks.
	 */
	@Test
	void ingestsEveryFetchedItemFromEveryConcurrentlyFetchedSourceWithNoLocalFiltering() {
		String categoryFeedPath = "/feed-" + UUID.randomUUID() + ".xml";
		String categoryRss = """
				<?xml version="1.0" encoding="UTF-8"?>
				<rss version="2.0">
				  <channel>
				    <title>Test Feed</title>
				    <item>
				      <title>Ukraine ceasefire talks resume</title>
				      <link>https://example.com/on-topic</link>
				      <pubDate>Tue, 02 Jan 2024 00:00:00 GMT</pubDate>
				    </item>
				    <item>
				      <title>Local weather forecast for the weekend</title>
				      <link>https://example.com/off-topic</link>
				      <pubDate>Tue, 02 Jan 2024 00:00:00 GMT</pubDate>
				    </item>
				  </channel>
				</rss>
				""";
		stubFor(get(urlEqualTo(categoryFeedPath)).willReturn(
				aResponse().withHeader("Content-Type", "application/rss+xml").withBody(categoryRss)));
		stubValidChatCompletion();
		UserId owner = newUser();
		CategoryId categoryId = newCategoryWithFeedAt(categoryFeedPath);
		Topic topic = topicRepository
				.save(Topic.create(owner, "War in Ukraine", categoryId, Instant.now()));
		String googleNewsRss = """
				<?xml version="1.0" encoding="UTF-8"?>
				<rss version="2.0">
				  <channel>
				    <title>Google News</title>
				    <item>
				      <title>Completely unrelated wire item</title>
				      <link>https://example.com/search-result</link>
				      <pubDate>Tue, 02 Jan 2024 00:00:00 GMT</pubDate>
				    </item>
				  </channel>
				</rss>
				""";
		stubFor(get(urlPathEqualTo("/rss/search")).withQueryParam("q", equalTo(topic.name())).willReturn(
				aResponse().withHeader("Content-Type", "application/rss+xml").withBody(googleNewsRss)));

		Briefing briefing = briefingService.generateBriefing(topic.id(), owner);

		assertThat(briefing.ingestedItems()).extracting(IngestedItem::title).containsExactlyInAnyOrder(
				"Ukraine ceasefire talks resume", "Local weather forecast for the weekend",
				"Completely unrelated wire item");
	}

	@Test
	void secondGenerationForTheSameTopicProducesADeltaBriefing() {
		String feedPath = "/feed-" + UUID.randomUUID() + ".xml";
		stubFor(get(urlEqualTo(feedPath)).willReturn(
				aResponse().withHeader("Content-Type", "application/rss+xml").withBody(VALID_RSS)));
		stubValidChatCompletion();
		UserId owner = newUser();
		TopicId topicId = newTopic(owner, newCategoryWithFeedAt(feedPath));

		Briefing first = briefingService.generateBriefing(topicId, owner);
		Briefing second = briefingService.generateBriefing(topicId, owner);

		assertThat(first.type()).isEqualTo(BriefingType.ONBOARDING);
		assertThat(second.type()).isEqualTo(BriefingType.DELTA);
	}

	@Test
	void aFailingSourceDoesNotBlockGeneration() {
		// No stub registered for this path — WireMock returns 404, which
		// RomeSourceContentFetcher treats as an unavailable source (proceed
		// with partial sources, per plan.md).
		String unstubbedFeedPath = "/feed-" + UUID.randomUUID() + ".xml";
		stubValidChatCompletion();
		UserId owner = newUser();
		TopicId topicId = newTopic(owner, newCategoryWithFeedAt(unstubbedFeedPath));

		Briefing briefing = briefingService.generateBriefing(topicId, owner);

		assertThat(briefing.id()).isNotNull();
		assertThat(briefing.ingestedItems()).isEmpty();
		assertThat(briefing.keyChanges()).isEqualTo("changes");
	}

	/**
	 * FR-009's "manual generation resets the schedule" decision: every
	 * successful generation advances {@code nextDueAt} the same way,
	 * whether triggered manually (as here) or by the scheduler — see
	 * {@link TopicRepository#recordSuccessfulGeneration}.
	 */
	@Test
	void generatingABriefingAdvancesTheTopicsNextDueAt() {
		String feedPath = "/feed-" + UUID.randomUUID() + ".xml";
		stubFor(get(urlEqualTo(feedPath)).willReturn(
				aResponse().withHeader("Content-Type", "application/rss+xml").withBody(VALID_RSS)));
		stubValidChatCompletion();
		UserId owner = newUser();
		TopicId topicId = newTopic(owner, newCategoryWithFeedAt(feedPath));

		Briefing briefing = briefingService.generateBriefing(topicId, owner);

		Topic updated = topicRepository.findByIdAndUserId(topicId, owner).orElseThrow();
		assertThat(updated.nextDueAt())
				.isEqualTo(ScheduleCalculator.nextDueAt(briefing.generatedAt(), Frequency.DAILY, null));
	}

	@Test
	void aFailingAiCallSurfacesGenerationFailedExceptionAndPersistsNothing() {
		String feedPath = "/feed-" + UUID.randomUUID() + ".xml";
		stubFor(get(urlEqualTo(feedPath)).willReturn(
				aResponse().withHeader("Content-Type", "application/rss+xml").withBody(VALID_RSS)));
		stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withStatus(500)));
		UserId owner = newUser();
		TopicId topicId = newTopic(owner, newCategoryWithFeedAt(feedPath));

		assertThatExceptionOfType(GenerationFailedException.class)
				.isThrownBy(() -> briefingService.generateBriefing(topicId, owner));
		assertThat(briefingService.listSummaries(topicId, owner)).isEmpty();
	}

	@Test
	void getHistoryReturnsTheTopicNameAndSummariesForTheOwner() {
		String feedPath = "/feed-" + UUID.randomUUID() + ".xml";
		stubFor(get(urlEqualTo(feedPath)).willReturn(
				aResponse().withHeader("Content-Type", "application/rss+xml").withBody(VALID_RSS)));
		stubValidChatCompletion();
		UserId owner = newUser();
		TopicId topicId = newTopic(owner, newCategoryWithFeedAt(feedPath));
		Briefing briefing = briefingService.generateBriefing(topicId, owner);

		BriefingService.TopicHistory history = briefingService.getHistory(topicId, owner).orElseThrow();

		assertThat(history.summaries()).hasSize(1);
		assertThat(history.summaries().get(0).id()).isEqualTo(briefing.id());
	}

	/**
	 * Distinguishes "owned, zero briefings yet" from "not owned" — unlike
	 * {@link BriefingService#listSummaries}, which collapses both into an
	 * empty list, {@code getHistory} must still return a present {@code
	 * Optional} here so the web layer can render an empty state rather than
	 * redirect away from a topic the caller actually owns.
	 */
	@Test
	void getHistoryReturnsAnEmptySummaryListForATopicWithNoBriefingsYet() {
		UserId owner = newUser();
		TopicId topicId = newTopic(owner, newCategoryWithFeedAt("/feed-" + UUID.randomUUID() + ".xml"));

		Optional<BriefingService.TopicHistory> history = briefingService.getHistory(topicId, owner);

		assertThat(history).isPresent();
		assertThat(history.get().summaries()).isEmpty();
	}

	@Test
	void getHistoryIsEmptyForATopicNotOwnedByTheCaller() {
		UserId owner = newUser();
		UserId otherUser = newUser();
		TopicId topicId = newTopic(owner, newCategoryWithFeedAt("/feed-" + UUID.randomUUID() + ".xml"));

		assertThat(briefingService.getHistory(topicId, otherUser)).isEmpty();
	}

	@Test
	void generatingABriefingForAnOptedInTopicSendsAnEmailWithRecognizableContent() {
		String feedPath = "/feed-" + UUID.randomUUID() + ".xml";
		stubFor(get(urlEqualTo(feedPath)).willReturn(
				aResponse().withHeader("Content-Type", "application/rss+xml").withBody(VALID_RSS)));
		stubValidChatCompletion();
		UserId owner = newUser();
		String ownerEmail = userRepository.findEmailById(owner).orElseThrow();
		TopicId topicId = newTopic(owner, newCategoryWithFeedAt(feedPath), true);

		briefingService.generateBriefing(topicId, owner);

		assertThat(fakeEmailSender.sentEmails()).filteredOn(sent -> sent.to().equals(ownerEmail))
				.hasSize(1)
				.first()
				.satisfies(sent -> {
					assertThat(sent.subject()).contains("first briefing");
					assertThat(sent.body()).contains("changes");
				});
	}

	@Test
	void generatingABriefingForAnOptedOutTopicSendsNoEmail() {
		String feedPath = "/feed-" + UUID.randomUUID() + ".xml";
		stubFor(get(urlEqualTo(feedPath)).willReturn(
				aResponse().withHeader("Content-Type", "application/rss+xml").withBody(VALID_RSS)));
		stubValidChatCompletion();
		UserId owner = newUser();
		String ownerEmail = userRepository.findEmailById(owner).orElseThrow();
		TopicId topicId = newTopic(owner, newCategoryWithFeedAt(feedPath), false);

		briefingService.generateBriefing(topicId, owner);

		assertThat(fakeEmailSender.sentEmails()).noneMatch(sent -> sent.to().equals(ownerEmail));
	}

	/**
	 * FR-012's "opted in means every generated briefing" — the onboarding
	 * briefing (a topic's first) must be emailed the same as any later delta
	 * briefing, with no type-based branching at the one shared hook point.
	 */
	@Test
	void bothOnboardingAndDeltaBriefingsAreEmailedForAnOptedInTopic() {
		String feedPath = "/feed-" + UUID.randomUUID() + ".xml";
		stubFor(get(urlEqualTo(feedPath)).willReturn(
				aResponse().withHeader("Content-Type", "application/rss+xml").withBody(VALID_RSS)));
		stubValidChatCompletion();
		UserId owner = newUser();
		String ownerEmail = userRepository.findEmailById(owner).orElseThrow();
		TopicId topicId = newTopic(owner, newCategoryWithFeedAt(feedPath), true);

		briefingService.generateBriefing(topicId, owner);
		briefingService.generateBriefing(topicId, owner);

		assertThat(fakeEmailSender.sentEmails()).filteredOn(sent -> sent.to().equals(ownerEmail))
				.extracting(sent -> sent.subject().contains("first briefing") ? "ONBOARDING" : "DELTA")
				.containsExactlyInAnyOrder("ONBOARDING", "DELTA");
	}

	@Test
	void deltaBriefingSubjectMentionsTheTopicsFrequencyAdjective() {
		UserId owner = newUser();
		String ownerEmail = userRepository.findEmailById(owner).orElseThrow();

		for (Frequency frequency : Frequency.values()) {
			String feedPath = "/feed-" + UUID.randomUUID() + ".xml";
			stubFor(get(urlEqualTo(feedPath)).willReturn(
					aResponse().withHeader("Content-Type", "application/rss+xml").withBody(VALID_RSS)));
			stubValidChatCompletion();
			String topicName = "Test Topic " + UUID.randomUUID();
			Topic topic = Topic.create(owner, topicName, newCategoryWithFeedAt(feedPath), Instant.now());
			topic.applySchedule(frequency, topic.preferredTime(), true, topic.nextDueAt());
			TopicId topicId = topicRepository.save(topic).id();

			briefingService.generateBriefing(topicId, owner); // onboarding — no frequency wording
			briefingService.generateBriefing(topicId, owner); // delta — should mention the frequency adjective

			assertThat(fakeEmailSender.sentEmails())
					.filteredOn(sent -> sent.to().equals(ownerEmail) && sent.subject().contains(topicName))
					.extracting(sent -> sent.subject())
					.anyMatch(subject -> subject.contains(frequency.emailAdjective()));
		}
	}

}
