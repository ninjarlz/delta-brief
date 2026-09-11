package pl.tul.deltabrief.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import pl.tul.deltabrief.auth.application.port.out.UserRepository;
import pl.tul.deltabrief.auth.domain.User;
import pl.tul.deltabrief.config.TestcontainersDatasourceConfig;

/**
 * Reuses the exact same context shape as {@code DeltaBriefApplicationTests}
 * (same {@code @Import}, no extra profiles/mocked beans) so Spring's test
 * context cache shares one Testcontainers-backed datasource rather than
 * standing up a second context — see {@link TestcontainersDatasourceConfig}'s
 * single-fixed-local-port constraint.
 */
@SpringBootTest
@Import(TestcontainersDatasourceConfig.class)
class UserRepositoryAdapterTest {

	@Autowired
	private UserRepository userRepository;

	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	@Test
	void savesAndFindsByEmail() {
		String email = uniqueEmail();
		User user = User.register(email, "hashed-password", Instant.now());

		userRepository.save(user);

		assertThat(userRepository.findByEmail(email)).isPresent();
		assertThat(userRepository.existsByEmail(email)).isTrue();
	}

	@Test
	void rejectsDuplicateEmail() {
		String email = uniqueEmail();
		userRepository.save(User.register(email, "hashed-password", Instant.now()));

		assertThatException()
				.isThrownBy(() -> userRepository.save(User.register(email, "another-hash", Instant.now())))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

}
