package pl.tul.deltabrief.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UserTests {

	private static User newUser() {
		return User.register("user@example.com", "hashed-password", Instant.now());
	}

	@Test
	void verifySucceedsForAMatchingUnexpiredToken() {
		User user = newUser();
		Instant now = Instant.now();
		user.issueVerificationToken("the-token", now.plus(Duration.ofHours(24)));

		boolean verified = user.verify("the-token", now.plus(Duration.ofHours(1)));

		assertThat(verified).isTrue();
		assertThat(user.emailVerified()).isTrue();
		assertThat(user.verificationToken()).isNull();
		assertThat(user.verificationTokenExpiresAt()).isNull();
	}

	@Test
	void verifyFailsForAWrongToken() {
		User user = newUser();
		Instant now = Instant.now();
		user.issueVerificationToken("the-token", now.plus(Duration.ofHours(24)));

		boolean verified = user.verify("wrong-token", now);

		assertThat(verified).isFalse();
		assertThat(user.emailVerified()).isFalse();
	}

	@Test
	void verifyFailsForAnExpiredToken() {
		User user = newUser();
		Instant now = Instant.now();
		user.issueVerificationToken("the-token", now.plus(Duration.ofHours(24)));

		boolean verified = user.verify("the-token", now.plus(Duration.ofHours(25)));

		assertThat(verified).isFalse();
		assertThat(user.emailVerified()).isFalse();
	}

}
