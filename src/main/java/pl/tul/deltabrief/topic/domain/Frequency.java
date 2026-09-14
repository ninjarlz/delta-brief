package pl.tul.deltabrief.topic.domain;

import java.time.Duration;

/**
 * How often a topic's briefing should be generated automatically (FR-008).
 * Every value carries a concrete interval — there is no "no schedule" state.
 */
public enum Frequency {

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

	/**
	 * Lowercase adjective used in outbound email wording (e.g. "It's your
	 * daily delta briefing for ..."). Lives here, not in the briefing
	 * module, so the mapping stays exhaustive and colocated with the enum it
	 * describes — see {@code TopicRepositoryAdapter}, which translates this
	 * to a plain {@code String} before it crosses into {@code briefing}.
	 */
	public String emailAdjective() {
		return switch (this) {
			case DAILY -> "daily";
			case EVERY_OTHER_DAY -> "every-other-day";
			case WEEKLY -> "weekly";
		};
	}

}
