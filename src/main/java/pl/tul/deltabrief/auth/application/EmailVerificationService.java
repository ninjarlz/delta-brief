package pl.tul.deltabrief.auth.application;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import pl.tul.deltabrief.auth.application.port.out.UserRepository;
import pl.tul.deltabrief.auth.domain.User;

/**
 * Handles a verification-link click.
 */
@Service
public class EmailVerificationService {

	private final UserRepository userRepository;

	public EmailVerificationService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	/**
	 * @return whether verification succeeded — false for an unknown or
	 *         expired token, letting the caller decide the user-facing message.
	 */
	public boolean verify(String token) {
		Optional<User> user = userRepository.findByVerificationToken(token);
		if (user.isEmpty()) {
			return false;
		}
		boolean verified = user.get().verify(token, Instant.now());
		if (verified) {
			userRepository.save(user.get());
		}
		return verified;
	}

}
