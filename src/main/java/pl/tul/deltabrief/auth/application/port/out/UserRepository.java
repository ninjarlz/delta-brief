package pl.tul.deltabrief.auth.application.port.out;

import java.util.Optional;
import pl.tul.deltabrief.auth.domain.User;
import pl.tul.deltabrief.auth.domain.UserId;

/**
 * Port the application layer depends on for {@link User} persistence,
 * implemented by the persistence adapter — keeps {@code application} free of
 * JPA imports.
 */
public interface UserRepository {

	User save(User user);

	Optional<User> findByEmail(String email);

	Optional<User> findByVerificationToken(String token);

	boolean existsByEmail(String email);

	/**
	 * Email-only lookup for cross-module consumers (e.g. {@code briefing},
	 * resolving a briefing email's recipient) that must never import the full
	 * {@link User} aggregate — see {@link UserId}'s own Javadoc: "other
	 * modules reference a user only by this value, never by importing User
	 * itself."
	 */
	Optional<String> findEmailById(UserId id);

}
