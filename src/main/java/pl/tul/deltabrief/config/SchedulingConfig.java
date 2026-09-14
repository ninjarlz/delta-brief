package pl.tul.deltabrief.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's {@code @Scheduled} task execution (FR-009) — in-process
 * on the app's always-on Render Standard instance, deliberately not a
 * separate Render Cron Job (which runs in its own container and couldn't
 * share this app's Spring context or Hikari connection pool; see
 * infrastructure.md).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

}
