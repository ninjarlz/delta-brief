package pl.tul.deltabrief.config;

import java.util.concurrent.Executor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Backs {@code @Async("emailTaskExecutor")}, used by two async email
 * senders: {@code RegistrationService.resendVerification} (dispatching the
 * verification-email send off the request thread closes a timing
 * side-channel — a real SMTP round-trip vs. a near-instant no-op would
 * otherwise let an attacker infer account state from response latency
 * alone) and {@code BriefingEmailNotifier.sendBriefingEmail} (keeps briefing
 * email delivery off both the manual generation request and the scheduler's
 * per-topic dispatch). Small pool: still the only async work in this app.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

	@Bean(name = "emailTaskExecutor")
	@ConditionalOnProperty(prefix = "app.async.email", name = "enabled", havingValue = "true", matchIfMissing = true)
	public Executor emailTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(50);
		executor.setThreadNamePrefix("email-task-");
		// Without this, a queued/in-flight send is abruptly abandoned mid-shutdown
		// (e.g. a Render redeploy) rather than allowed to finish. 10s comfortably
		// covers a single send's worst case under the 5s SMTP connect/read/write
		// timeouts already configured, without meaningfully delaying shutdown.
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(10);
		executor.initialize();
		return executor;
	}

}
