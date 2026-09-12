package pl.tul.deltabrief.config;

import java.util.concurrent.Executor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Provides the "emailTaskExecutor" bean as a same-thread executor for
 * tests, so {@code @Async} methods (e.g.
 * {@code RegistrationService.resendVerification}) run synchronously —
 * otherwise assertions immediately following a call would race the
 * background thread. Test classes importing this must also set
 * {@code app.async.email.enabled=false} (e.g. via
 * {@code @SpringBootTest(properties = ...)}), which stops
 * {@code AsyncConfig}'s real threaded executor from being registered at
 * all — deliberately avoiding a same-name bean *override* (whose winner
 * depends on Spring's internal configuration-processing order) in favor
 * of only ever having one bean definition active. Imported alongside
 * {@link TestcontainersDatasourceConfig} on every test class sharing the
 * cached Spring context, so the import set stays identical across them
 * (see that class's single-fixed-local-port constraint).
 */
@TestConfiguration
public class SynchronousAsyncConfig {

	@Bean(name = "emailTaskExecutor")
	public Executor emailTaskExecutor() {
		return Runnable::run;
	}

}
