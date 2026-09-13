package pl.tul.deltabrief.briefing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.wiremock.spring.EnableWireMock;
import pl.tul.deltabrief.config.SynchronousAsyncConfig;
import pl.tul.deltabrief.config.TestcontainersDatasourceConfig;
import pl.tul.deltabrief.shared.adapter.out.email.FakeEmailSender;

/**
 * Full generate -> view latest -> view history -> view a past briefing ->
 * cross-user isolation flow. Both external calls (RSS fetch, OpenAI) are
 * WireMock-stubbed — no real network call happens. {@code @Transactional}
 * rolls back the test category/source fixture at the end (see
 * {@link pl.tul.deltabrief.briefing.application.BriefingServiceTests} for
 * why). Authenticates via the real register -&gt; verify -&gt; login HTTP
 * flow, mirroring {@code TopicFlowIntegrationTests}.
 */
@SpringBootTest(properties = {"app.async.email.enabled=false", "bucket4j.enabled=false", "spring.cache.type=none",
		"spring.ai.openai.base-url=${wiremock.server.baseUrl}"})
@Import({TestcontainersDatasourceConfig.class, SynchronousAsyncConfig.class})
@EnableWireMock
@Transactional
class BriefingFlowIntegrationTests {

	private static final Pattern TOKEN_PATTERN = Pattern.compile("token=([\\w-]+)");

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
	private WebApplicationContext webApplicationContext;

	@Autowired
	private FakeEmailSender fakeEmailSender;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Value("${wiremock.server.baseUrl}")
	private String wireMockBaseUrl;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
			.apply(springSecurity())
			.build();
		SecurityContextHolder.clearContext();
		WireMock.reset();
	}

	private Long newCategoryWithFeedAt(String feedPath) {
		Long categoryId = jdbcTemplate.queryForObject("insert into categories(name) values (?) returning id",
				Long.class, "Test Category " + UUID.randomUUID());
		jdbcTemplate.update("insert into sources(category_id, name, feed_url) values (?, ?, ?)", categoryId,
				"Test Source", wireMockBaseUrl + feedPath);
		return categoryId;
	}

	@Test
	void generatesViewsHistoryAndEnforcesCrossUserIsolation() throws Exception {
		String feedPath = "/feed-" + UUID.randomUUID() + ".xml";
		stubFor(WireMock.get(urlEqualTo(feedPath)).willReturn(
				aResponse().withHeader("Content-Type", "application/rss+xml").withBody(VALID_RSS)));
		stubFor(WireMock.post(urlEqualTo("/chat/completions")).willReturn(
				aResponse().withHeader("Content-Type", "application/json").withBody(CHAT_COMPLETION_RESPONSE)));

		MockHttpSession session = loginAsNewVerifiedUser();
		Long categoryId = newCategoryWithFeedAt(feedPath);

		mockMvc.perform(post("/topics").with(csrf()).session(session)
				.param("name", "Test Topic")
				.param("categoryId", categoryId.toString()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));
		String topicId = extractCreatedTopicId(session);

		mockMvc.perform(post("/topics/" + topicId + "/briefings").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/topics/" + topicId + "/briefings/latest"));

		mockMvc.perform(get("/topics/" + topicId + "/briefings/latest").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Onboarding briefing")))
			.andExpect(content().string(containsString("Test Headline")));

		mockMvc.perform(post("/topics/" + topicId + "/briefings").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection());

		String latestBody = mockMvc.perform(get("/topics/" + topicId + "/briefings/latest").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Delta briefing")))
			.andReturn().getResponse().getContentAsString();
		assertThat(latestBody).contains("Onboarding briefing");
		String firstBriefingId = extractHistoryLinkedBriefingId(latestBody, topicId);

		mockMvc.perform(get("/topics/" + topicId + "/briefings/" + firstBriefingId).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Onboarding briefing")));

		// Cross-user isolation: another user can't view this briefing by
		// guessing its ID — the topic itself isn't theirs, so the ownership
		// check fails before the briefing ID is even considered.
		MockHttpSession otherSession = loginAsNewVerifiedUser();
		mockMvc.perform(get("/topics/" + topicId + "/briefings/" + firstBriefingId).session(otherSession))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));
	}

	private MockHttpSession loginAsNewVerifiedUser() throws Exception {
		String email = "briefing-flow-" + UUID.randomUUID() + "@example.com";
		String password = "correct-horse-battery-staple";

		mockMvc.perform(post("/register").with(csrf())
				.param("email", email)
				.param("password", password)
				.param("confirmPassword", password))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/check-email"));

		String token = extractToken(email);
		mockMvc.perform(get("/verify").param("token", token))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login?verified"));

		return (MockHttpSession) mockMvc.perform(post("/login").with(csrf())
				.param("username", email)
				.param("password", password))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"))
			.andReturn().getRequest().getSession(false);
	}

	private String extractCreatedTopicId(MockHttpSession session) throws Exception {
		String body = mockMvc.perform(get("/").session(session)).andReturn().getResponse().getContentAsString();
		Matcher matcher = Pattern.compile("/topics/(\\d+)/delete").matcher(body);
		if (!matcher.find()) {
			throw new IllegalStateException("No topic delete form found in list page body: " + body);
		}
		return matcher.group(1);
	}

	private String extractHistoryLinkedBriefingId(String body, String topicId) {
		Matcher matcher = Pattern.compile("/topics/" + topicId + "/briefings/(\\d+)\"").matcher(body);
		if (!matcher.find()) {
			throw new IllegalStateException("No history link found in briefing page body: " + body);
		}
		return matcher.group(1);
	}

	private String extractToken(String email) {
		String body = fakeEmailSender.sentEmails().stream()
				.filter(sent -> sent.to().equals(email))
				.findFirst()
				.orElseThrow()
				.body();
		Matcher matcher = TOKEN_PATTERN.matcher(body);
		if (!matcher.find()) {
			throw new IllegalStateException("No verification token found in email body: " + body);
		}
		return matcher.group(1);
	}

}
