package pl.tul.deltabrief.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import pl.tul.deltabrief.config.TestcontainersDatasourceConfig;
import pl.tul.deltabrief.shared.adapter.out.email.FakeEmailSender;

/**
 * Proves the full register -> verify -> login -> logout flow against a real
 * Postgres. Builds {@link MockMvc} manually from the {@link WebApplicationContext}
 * rather than using {@code @AutoConfigureMockMvc} — that annotation changes the
 * Spring context signature, which would force a second, separate context (and
 * a second Testcontainers container on the same fixed port) instead of reusing
 * the one shared by {@code DeltaBriefApplicationTests} and the other tests —
 * see {@link TestcontainersDatasourceConfig}'s single-fixed-local-port constraint.
 */
@SpringBootTest
@Import(TestcontainersDatasourceConfig.class)
class AuthFlowIntegrationTest {

	private static final Pattern TOKEN_PATTERN = Pattern.compile("token=([\\w-]+)");

	@Autowired
	private WebApplicationContext webApplicationContext;

	@Autowired
	private FakeEmailSender fakeEmailSender;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
				.apply(springSecurity())
				.build();
		SecurityContextHolder.clearContext();
	}

	@Test
	void registerVerifyLoginLogout() throws Exception {
		String email = "flow-" + UUID.randomUUID() + "@example.com";
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

		mockMvc.perform(post("/login").with(csrf())
				.param("username", email)
				.param("password", password))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));

		mockMvc.perform(post("/login").with(csrf())
				.param("username", email)
				.param("password", "wrong-password"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login?error"));

		mockMvc.perform(get("/some-protected-path"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));

		mockMvc.perform(post("/logout").with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login?logout"));
	}

	@Test
	void unverifiedAccountCannotLogIn() throws Exception {
		String email = "unverified-" + UUID.randomUUID() + "@example.com";
		String password = "correct-horse-battery-staple";

		mockMvc.perform(post("/register").with(csrf())
				.param("email", email)
				.param("password", password)
				.param("confirmPassword", password))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/check-email"));

		mockMvc.perform(post("/login").with(csrf())
				.param("username", email)
				.param("password", password))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login?unverified"));

		mockMvc.perform(get("/some-protected-path"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
	}

	@Test
	void defaultViewRedirectsAnonymousVisitorsToLogin() throws Exception {
		mockMvc.perform(get("/"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
	}

	@Test
	void defaultViewShowsPlaceholderForAuthenticatedVisitors() throws Exception {
		mockMvc.perform(get("/").with(user("someone@example.com")))
			.andExpect(status().isOk());
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
