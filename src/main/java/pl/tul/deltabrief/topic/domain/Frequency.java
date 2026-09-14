package pl.tul.deltabrief.topic.domain;

import java.time.Duration;

/**
 * How often a topic's briefing should be generated automatically (FR-008).
 * {@link #MANUAL} carries no interval — a manual-only topic never has a
 * {@code nextDueAt} and is never picked up by the scheduler.
 */
public enum Frequency {

	MANUAL(null),
	TWICE_DAILY(Duration.ofHours(12)),
	DAILY(Duration.ofDays(1)),
	EVERY_OTHER_DAY(Duration.ofDays(2)),
	WEEKLY(Duration.ofDays(7));

	private final Duration interval;

	Frequency(Duration interval) {
		this.interval = interval;
	}

	public Duration interval() {
		return interval;
	}

}
