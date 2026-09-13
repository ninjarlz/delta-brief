package pl.tul.deltabrief.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import pl.tul.deltabrief.auth.application.RegistrationService.EmailAlreadyRegisteredException;
import pl.tul.deltabrief.auth.application.dto.RegistrationRequest;
import pl.tul.deltabrief.auth.application.port.out.UserRepository;
import pl.tul.deltabrief.auth.domain.User;
import pl.tul.deltabrief.config.SynchronousAsyncConfig;
import pl.tul.deltabrief.config.TestcontainersDatasourceConfig;
import pl.tul.deltabrief.shared.adapter.out.email.FakeEmailSender;
import pl.tul.deltabrief.shared.application.EmailDeliveryException;

/**
 * Reuses the exact same context shape as {@code DeltaBriefApplicationTests}
 * (same {@code @Import}) so Spring's test context cache shares one
 * Testcontainers-backed datasource — see {@link TestcontainersDatasourceConfig}'s
 * single-fixed-local-port constraint.
 */
@SpringBootTest(properties = "app.async.email.enabled=false")
@Import({TestcontainersDatasourceConfig.class, SynchronousAsyncConfig.class})
class RegistrationServiceTests {

	private static final Pattern TOKEN_PATTERN = Pattern.compile("token=([\\w-]+)");

	@Autowired
	private RegistrationService registrationService;

	@Autowired
	private EmailVerificationService emailVerificationService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private FakeEmailSender fakeEmailSender;

	private static String uniqueEmail() {
		return "reg-" + UUID.randomUUID() + "@example.com";
	}

	private static RegistrationRequest requestFor(String email) {
		RegistrationRequest request = new RegistrationRequest();
		request.setEmail(email);
		request.setPassword("correct-horse-battery-staple");
		request.setConfirmPassword("correct-horse-battery-staple");
		return request;
	}

	@Test
	void registrationIssuesAVerificationTokenAndSendsAnEmail() {
		String email = uniqueEmail();

		registrationService.register(requestFor(email));

		User saved = userRepository.findByEmail(email).orElseThrow();
		assertThat(saved.emailVerified()).isFalse();
		assertThat(saved.verificationToken()).isNotBlank();
		assertThat(fakeEmailSender.sentEmails()).anyMatch(sent -> sent.to().equals(email));
	}

	@Test
	void rejectsRegistrationWithAnAlreadyRegisteredEmail() {
		String email = uniqueEmail();
		registrationService.register(requestFor(email));

		assertThatExceptionOfType(EmailAlreadyRegisteredException.class)
				.isThrownBy(() -> registrationService.register(requestFor(email)));
	}

	@Test
	void validTokenVerifiesTheUser() {
		String email = uniqueEmail();
		registrationService.register(requestFor(email));
		String token = extractToken(email);

		boolean verified = emailVerificationService.verify(token);

		assertThat(verified).isTrue();
		assertThat(userRepository.findByEmail(email).orElseThrow().emailVerified()).isTrue();
	}

	@Test
	void unknownTokenDoesNotVerifyAnyone() {
		boolean verified = emailVerificationService.verify("not-a-real-token");

		assertThat(verified).isFalse();
	}

	@Test
	void resendVerificationIssuesAFreshTokenAndInvalidatesTheOldOne() {
		String email = uniqueEmail();
		registrationService.register(requestFor(email));
		String originalToken = extractToken(email);

		registrationService.resendVerification(email);

		User afterResend = userRepository.findByEmail(email).orElseThrow();
		assertThat(afterResend.verificationToken()).isNotEqualTo(originalToken);
		assertThat(fakeEmailSender.sentEmails().stream().filter(sent -> sent.to().equals(email)).count())
				.isEqualTo(2);
		assertThat(emailVerificationService.verify(originalToken)).isFalse();
		assertThat(emailVerificationService.verify(afterResend.verificationToken())).isTrue();
	}

	@Test
	void resendVerificationIsANoOpForAnAlreadyVerifiedAccount() {
		String email = uniqueEmail();
		registrationService.register(requestFor(email));
		emailVerificationService.verify(extractToken(email));

		registrationService.resendVerification(email);

		assertThat(fakeEmailSender.sentEmails().stream().filter(sent -> sent.to().equals(email)).count())
				.isEqualTo(1);
	}

	@Test
	void resendVerificationIsANoOpForAnUnknownEmail() {
		String unknownEmail = uniqueEmail();

		registrationService.resendVerification(unknownEmail);

		assertThat(fakeEmailSender.sentEmails()).noneMatch(sent -> sent.to().equals(unknownEmail));
	}

	/**
	 * Regression guardrail, not a test against a currently-exploitable leak —
	 * research confirmed no leak exists today (EmailDeliveryException's
	 * message is hardcoded, never derived from the wrapped MailException).
	 * This locks in the structural guarantee: the mail-failure log call must
	 * never be passed a raw exception/Throwable object, which the current
	 * log4j2.xml pattern layout would auto-print (cause chain included) if
	 * one ever were.
	 */
	@Test
	void mailFailureLogNeverReceivesARawException() {
		String email = uniqueEmail();
		Logger logger = (Logger) LogManager.getLogger(RegistrationService.class);
		CapturingAppender appender = new CapturingAppender();
		appender.start();
		logger.addAppender(appender);
		try {
			fakeEmailSender.failNextSendWith(new EmailDeliveryException("Failed to send email to " + email,
					new RuntimeException("simulated SMTP failure")));

			registrationService.register(requestFor(email));

			assertThat(appender.events()).isNotEmpty();
			assertThat(appender.events()).allSatisfy(event -> assertThat(event.getThrown()).isNull());
		} finally {
			logger.removeAppender(appender);
			appender.stop();
		}
	}

	private static final class CapturingAppender extends AbstractAppender {

		private final List<LogEvent> events = new CopyOnWriteArrayList<>();

		CapturingAppender() {
			super("capturing-test-appender", null, null, true, Property.EMPTY_ARRAY);
		}

		@Override
		public void append(LogEvent event) {
			events.add(event.toImmutable());
		}

		List<LogEvent> events() {
			return events;
		}

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
