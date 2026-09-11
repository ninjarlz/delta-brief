package pl.tul.deltabrief.auth.application.port.out;

import java.util.Optional;
import pl.tul.deltabrief.auth.domain.User;

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

}
