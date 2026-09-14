package pl.tul.deltabrief.briefing.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pl.tul.deltabrief.topic.application.port.out.DueTopic;
import pl.tul.deltabrief.topic.application.port.out.TopicRepository;

/**
 * Polls for topics due for automatic generation (FR-009) and dispatches
 * them through the exact same {@link BriefingService#generateBriefing}
 * path a manual "Generate now" click uses — {@code generateBriefing}
 * itself advances the schedule on success (see its Javadoc), so this class
 * only has to handle the failure side: recording the attempt and moving
 * on. Leaving {@code nextDueAt} unchanged on failure means the topic stays
 * due and the next poll cycle retries it naturally — no separate backoff
 * or auto-pause logic (see plan.md's explicit "retry every cycle
 * indefinitely" decision).
 *
 * <p>Dispatch is bounded (not one thread per due topic) to stay under the
 * app's small Hikari connection pool: a fixed-size thread pool sized from
 * {@code app.scheduling.max-concurrent-generations} caps how many
 * generations run at once — any due topics beyond that simply queue for
 * the next free worker, no separate semaphore/limiter needed. Plain
 * platform threads are fine here since the pool size is small and fixed
 * by config (not fanned out per due topic); the per-source fetch fan-out
 * inside each individual {@code generateBriefing} call still uses virtual
 * threads (see {@link BriefingService#generateBriefing}).
 */
@Component
class ScheduledBriefingRunner {

	private final TopicRepository topicRepository;
	private final BriefingService briefingService;
	private final int maxConcurrentGenerations;

	ScheduledBriefingRunner(TopicRepository topicRepository, BriefingService briefingService,
			@Value("${app.scheduling.max-concurrent-generations:3}") int maxConcurrentGenerations) {
		this.topicRepository = topicRepository;
		this.briefingService = briefingService;
		this.maxConcurrentGenerations = maxConcurrentGenerations;
	}

	// fixedDelay (not fixedRate): measures the interval from this method's own
	// completion, so overlapping ticks can't happen regardless of the task
	// scheduler's thread-pool size.
	@Scheduled(fixedDelayString = "${app.scheduling.poll-interval-ms:900000}")
	void runDueGenerations() {
		List<DueTopic> due = topicRepository.findDueForScheduledGeneration(Instant.now());
		if (due.isEmpty()) {
			return;
		}
		try (ExecutorService executor = Executors.newFixedThreadPool(maxConcurrentGenerations)) {
			List<Future<?>> futures = new ArrayList<>();
			for (DueTopic topic : due) {
				futures.add(executor.submit(() -> generateOne(topic)));
			}
			for (Future<?> future : futures) {
				awaitCompletion(future);
			}
		}
	}

	private void generateOne(DueTopic topic) {
		try {
			briefingService.generateBriefing(topic.id(), topic.userId());
		} catch (RuntimeException failed) {
			topicRepository.recordFailedScheduledGeneration(topic.id(), Instant.now());
		}
	}

	/**
	 * {@code generateOne} already catches everything {@code
	 * generateBriefing} can throw — nothing should surface here, but a poll
	 * tick shouldn't abort (leaving later futures un-awaited) if one
	 * somehow did.
	 */
	private static void awaitCompletion(Future<?> future) {
		try {
			future.get();
		} catch (ExecutionException ignored) {
			// see method Javadoc
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
