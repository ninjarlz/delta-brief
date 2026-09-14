package pl.tul.deltabrief.topic;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import pl.tul.deltabrief.config.SynchronousAsyncConfig;
import pl.tul.deltabrief.config.TestcontainersDatasourceConfig;
import pl.tul.deltabrief.shared.adapter.out.email.FakeEmailSender;
import pl.tul.deltabrief.topic.application.port.out.CategoryRepository;

/**
 * Proves the full create -> browse -> delete flow, plus cross-user
 * isolation. Builds {@link MockMvc} manually from the
 * {@link WebApplicationContext} and shares the same
 * {@code @SpringBootTest}/{@code @Import} signature as
 * {@code AuthFlowIntegrationTests} so Spring's test context cache is
 * shared — see {@link TestcontainersDatasourceConfig}'s
 * single-fixed-local-port constraint.
 *
 * <p>Authenticates via the real register -> verify -> login HTTP flow
 * (not the {@code .with(user(...))} MockMvc shortcut) — {@link
 * pl.tul.deltabrief.topic.adapter.in.web.TopicController} resolves the
 * current user by casting the principal to {@code AppUserDetails}, which
 * the shortcut doesn't produce.
 */
@SpringBootTest(properties = "app.async.email.enabled=false")
@Import({TestcontainersDatasourceConfig.class, SynchronousAsyncConfig.class})
class TopicFlowIntegrationTests {

	private static final Pattern TOKEN_PATTERN = Pattern.compile("token=([\\w-]+)");

	@Autowired
	private WebApplicationContext webApplicationContext;

	@Autowired
	private FakeEmailSender fakeEmailSender;

	@Autowired
	private CategoryRepository categoryRepository;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
				.apply(springSecurity())
				.build();
		SecurityContextHolder.clearContext();
	}

	@Test
	void unauthenticatedVisitorIsRedirectedToLogin() throws Exception {
		mockMvc.perform(get("/"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
	}

	@Test
	void newUserSeesTheEmptyStateThenCreatesBrowsesAndDeletesATopic() throws Exception {
		MockHttpSession session = loginAsNewVerifiedUser();
		Long categoryId = categoryRepository.findAll().get(0).id().value();
		String categoryName = categoryRepository.findAll().get(0).name();

		mockMvc.perform(get("/").session(session))
			.andExpect(status().isOk())
			.andExpect(view().name("topics"))
			.andExpect(content().string(containsString("first topic")));

		mockMvc.perform(post("/topics").with(csrf()).session(session)
				.param("name", "War in Ukraine")
				.param("categoryId", categoryId.toString()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));

		mockMvc.perform(get("/").session(session))
			.andExpect(status().isOk())
			.andExpect(view().name("topics"))
			.andExpect(content().string(allOf(containsString("War in Ukraine"), containsString(categoryName))));

		String topicId = extractCreatedTopicId(session);

		mockMvc.perform(post("/topics/" + topicId + "/delete").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));

		mockMvc.perform(get("/").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("War in Ukraine"))));
	}

	@Test
	void duplicateNameForTheSameUserShowsAnInlineErrorWithoutRedirecting() throws Exception {
		MockHttpSession session = loginAsNewVerifiedUser();
		Long categoryId = categoryRepository.findAll().get(0).id().value();
		mockMvc.perform(post("/topics").with(csrf()).session(session)
				.param("name", "War in Ukraine")
				.param("categoryId", categoryId.toString()))
			.andExpect(status().is3xxRedirection());

		mockMvc.perform(post("/topics").with(csrf()).session(session)
				.param("name", "WAR IN UKRAINE")
				.param("categoryId", categoryId.toString()))
			.andExpect(status().isOk())
			.andExpect(view().name("topic-form"));
	}

	@Test
	void topicsListShowsAViewBriefingsLinkForEachTopic() throws Exception {
		MockHttpSession session = loginAsNewVerifiedUser();
		Long categoryId = categoryRepository.findAll().get(0).id().value();
		mockMvc.perform(post("/topics").with(csrf()).session(session)
				.param("name", "War in Ukraine")
				.param("categoryId", categoryId.toString()))
			.andExpect(status().is3xxRedirection());
		String topicId = extractCreatedTopicId(session);

		mockMvc.perform(get("/").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("/topics/" + topicId + "/briefings")));
	}

	@Test
	void aUserCanEditATopicsSchedule() throws Exception {
		MockHttpSession session = loginAsNewVerifiedUser();
		Long categoryId = categoryRepository.findAll().get(0).id().value();
		mockMvc.perform(post("/topics").with(csrf()).session(session)
				.param("name", "War in Ukraine")
				.param("categoryId", categoryId.toString())
				.param("frequency", "DAILY"))
			.andExpect(status().is3xxRedirection());
		String topicId = extractCreatedTopicId(session);

		mockMvc.perform(get("/topics/" + topicId + "/edit").session(session))
			.andExpect(status().isOk())
			.andExpect(view().name("topic-edit"));

		mockMvc.perform(post("/topics/" + topicId + "/edit").with(csrf()).session(session)
				.param("frequency", "WEEKLY")
				.param("preferredHour", "9"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));

		mockMvc.perform(get("/topics/" + topicId + "/edit").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("selected")));
	}

	@Test
	void invalidPreferredHourOnEditShowsAnInlineErrorWithoutRedirecting() throws Exception {
		MockHttpSession session = loginAsNewVerifiedUser();
		Long categoryId = categoryRepository.findAll().get(0).id().value();
		mockMvc.perform(post("/topics").with(csrf()).session(session)
				.param("name", "War in Ukraine")
				.param("categoryId", categoryId.toString())
				.param("frequency", "DAILY"))
			.andExpect(status().is3xxRedirection());
		String topicId = extractCreatedTopicId(session);

		mockMvc.perform(post("/topics/" + topicId + "/edit").with(csrf()).session(session)
				.param("frequency", "WEEKLY")
				.param("preferredHour", "24"))
			.andExpect(status().isOk())
			.andExpect(view().name("topic-edit"));
	}

	@Test
	void aUserCannotEditAnotherUsersTopicSchedule() throws Exception {
		MockHttpSession sessionA = loginAsNewVerifiedUser();
		MockHttpSession sessionB = loginAsNewVerifiedUser();
		Long categoryId = categoryRepository.findAll().get(0).id().value();
		mockMvc.perform(post("/topics").with(csrf()).session(sessionA)
				.param("name", "User A topic")
				.param("categoryId", categoryId.toString())
				.param("frequency", "DAILY"))
			.andExpect(status().is3xxRedirection());
		String userAsTopicId = extractCreatedTopicId(sessionA);

		// No information leak — same convention as delete: redirects to "/"
		// regardless of ownership, rather than a distinguishable 404/403.
		mockMvc.perform(get("/topics/" + userAsTopicId + "/edit").session(sessionB))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));

		mockMvc.perform(post("/topics/" + userAsTopicId + "/edit").with(csrf()).session(sessionB)
				.param("frequency", "WEEKLY")
				.param("preferredHour", "9"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));
	}

	@Test
	void aUserCannotDeleteAnotherUsersTopic() throws Exception {
		MockHttpSession sessionA = loginAsNewVerifiedUser();
		MockHttpSession sessionB = loginAsNewVerifiedUser();
		Long categoryId = categoryRepository.findAll().get(0).id().value();

		mockMvc.perform(post("/topics").with(csrf()).session(sessionA)
				.param("name", "User A topic")
				.param("categoryId", categoryId.toString()))
			.andExpect(status().is3xxRedirection());
		String userAsTopicId = extractCreatedTopicId(sessionA);

		// User B's delete request against user A's topic ID redirects normally
		// (no information leak — see TopicService.deleteTopic), but the topic
		// itself must still exist afterward.
		mockMvc.perform(post("/topics/" + userAsTopicId + "/delete").with(csrf()).session(sessionB))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));

		mockMvc.perform(get("/").session(sessionA))
			.andExpect(content().string(containsString("User A topic")));
	}

	/**
	 * Registers, verifies, and logs in a brand-new user, returning the
	 * authenticated {@link MockHttpSession} — mirrors {@code
	 * AuthFlowIntegrationTests}' session-threading technique.
	 */
	private MockHttpSession loginAsNewVerifiedUser() throws Exception {
		String email = "topic-flow-" + UUID.randomUUID() + "@example.com";
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

	/**
	 * Parses the most recently created topic's ID out of its delete form's
	 * action URL on the rendered list page — avoids needing a repository
	 * autowired into this web-layer test just to look up an ID.
	 */
	private String extractCreatedTopicId(MockHttpSession session) throws Exception {
		String body = mockMvc.perform(get("/").session(session))
				.andReturn().getResponse().getContentAsString();
		Matcher matcher = Pattern.compile("/topics/(\\d+)/delete").matcher(body);
		if (!matcher.find()) {
			throw new IllegalStateException("No topic delete form found in list page body: " + body);
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
