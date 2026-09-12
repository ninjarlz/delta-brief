package pl.tul.deltabrief.auth.application;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.MailException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import pl.tul.deltabrief.auth.application.dto.RegistrationRequest;
import pl.tul.deltabrief.auth.application.port.out.UserRepository;
import pl.tul.deltabrief.auth.domain.User;
import pl.tul.deltabrief.shared.application.EmailSender;

/**
 * Orchestrates registration: uniqueness check, password hashing, verification
 * token issuance, and email dispatch.
 */
@Service
@Log4j2
public class RegistrationService {

	private static final Duration VERIFICATION_TOKEN_TTL = Duration.ofHours(24);

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final EmailSender emailSender;
	private final String baseUrl;

	public RegistrationService(UserRepository userRepository, PasswordEncoder passwordEncoder,
			EmailSender emailSender, @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.emailSender = emailSender;
		this.baseUrl = baseUrl;
	}

	/**
	 * @throws EmailAlreadyRegisteredException if the email is already taken —
	 *         either caught upfront or via the DB's unique-constraint fallback
	 *         when two registrations race past the upfront check.
	 */
	public void register(RegistrationRequest request) {
		if (userRepository.existsByEmail(request.getEmail())) {
			throw new EmailAlreadyRegisteredException(request.getEmail());
		}

		String passwordHash = passwordEncoder.encode(request.getPassword());
		User user = User.register(request.getEmail(), passwordHash, Instant.now());
		String token = issueNewVerificationToken(user);

		try {
			userRepository.save(user);
		} catch (DataIntegrityViolationException raceLostToDbConstraint) {
			throw new EmailAlreadyRegisteredException(request.getEmail());
		}

		sendVerificationEmail(request.getEmail(), token);
	}

	/**
	 * Reissues a verification link for an existing, not-yet-verified account.
	 * Silently no-ops for an unknown email or an already-verified account —
	 * the caller always shows the same generic "check your email" outcome
	 * regardless, so this can't be used to enumerate registered addresses.
	 */
	public void resendVerification(String email) {
		userRepository.findByEmail(email).ifPresent(user -> {
			if (user.emailVerified()) {
				return;
			}
			String token = issueNewVerificationToken(user);
			userRepository.save(user);
			sendVerificationEmail(email, token);
		});
	}

	private String issueNewVerificationToken(User user) {
		String token = UUID.randomUUID().toString();
		user.issueVerificationToken(token, Instant.now().plus(VERIFICATION_TOKEN_TTL));
		return token;
	}

	private void sendVerificationEmail(String email, String token) {
		String verificationLink = baseUrl + "/verify?token=" + token;
		try {
			emailSender.send(email, "Verify your DeltaBrief email address",
					"Click the link below to verify your email address:\n\n" + verificationLink);
		} catch (MailException emailDeliveryFailed) {
			// The account is already created; email_verified just stays false until
			// the user finds another way to verify. Verification never gates login,
			// so this is non-fatal.
			log.warn(">>> Failed to send verification email to {}: {}", email, emailDeliveryFailed.getMessage());
		}
	}

	public static class EmailAlreadyRegisteredException extends RuntimeException {
		public EmailAlreadyRegisteredException(String email) {
			super("Email already registered: " + email);
		}
	}

}
