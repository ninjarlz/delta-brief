package pl.tul.deltabrief.auth.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Plain unit test, no Spring context — {@link RegistrationRateLimiter} has
 * no framework dependencies. Cheapest layer proving the bucket logic
 * itself; {@code AuthFlowIntegrationTests} covers the thinner "is it
 * actually wired into the real HTTP path" proof.
 */
class RegistrationRateLimiterTests {

	@Test
	void capsAtTheEmailLimitRegardlessOfIp() {
		RegistrationRateLimiter limiter = new RegistrationRateLimiter();
		String email = "same@example.com";

		for (int i = 0; i < 5; i++) {
			assertThat(limiter.tryConsume(email, "ip-" + i)).isTrue();
		}
		assertThat(limiter.tryConsume(email, "ip-6")).isFalse();
	}

	@Test
	void capsAtTheIpLimitEvenWithFreshEmailsEachTime() {
		RegistrationRateLimiter limiter = new RegistrationRateLimiter();
		String ip = "10.0.0.1";

		for (int i = 0; i < 20; i++) {
			assertThat(limiter.tryConsume("user" + i + "@example.com", ip)).isTrue();
		}
		assertThat(limiter.tryConsume("brand-new@example.com", ip)).isFalse();
	}

	@Test
	void distinctEmailAndIpPairsDoNotInterfere() {
		RegistrationRateLimiter limiter = new RegistrationRateLimiter();

		for (int i = 0; i < 5; i++) {
			assertThat(limiter.tryConsume("a@example.com", "ip-a")).isTrue();
		}
		assertThat(limiter.tryConsume("a@example.com", "ip-a")).isFalse();

		// A completely different email+IP pair is unaffected by the exhausted pair above.
		assertThat(limiter.tryConsume("b@example.com", "ip-b")).isTrue();
	}

}
