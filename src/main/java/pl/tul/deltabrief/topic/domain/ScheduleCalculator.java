package pl.tul.deltabrief.topic.domain;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Pure computation of a topic's next scheduled-generation instant. No Spring
 * dependency, no wall-clock read — {@code anchor} (the last successful
 * generation's {@code generatedAt}, or {@code createdAt} when there is none
 * yet) is always passed in, so this is directly unit-testable.
 */
public final class ScheduleCalculator {

	/**
	 * The time of day scheduled generation nudges toward when a topic has no
	 * explicit {@code preferredTime} — a deliberate product default, not a
	 * conservative placeholder.
	 */
	private static final LocalTime DEFAULT_PREFERRED_TIME = LocalTime.of(9, 0);

	private ScheduleCalculator() {
	}

	/**
	 * @param anchor the timestamp the interval is measured from
	 * @param frequency how often to regenerate
	 * @param preferredTime UTC time-of-day to nudge the result toward;
	 * {@code null} falls back to {@link #DEFAULT_PREFERRED_TIME}. The app has
	 * no per-user timezone concept, so this is interpreted in UTC.
	 * @return the next due instant, always nudged forward (never earlier) to
	 * the effective preferred time — never {@code null}.
	 */
	public static Instant nextDueAt(Instant anchor, Frequency frequency, LocalTime preferredTime) {
		Instant base = anchor.plus(frequency.interval());
		LocalTime time = preferredTime != null ? preferredTime : DEFAULT_PREFERRED_TIME;
		return nudgeToPreferredTime(base, time);
	}

	/**
	 * Rounds {@code base} forward to the next UTC instant at {@code time} —
	 * never earlier than {@code base}. If {@code base} already falls exactly
	 * on {@code time}, it is returned unchanged rather than rolled forward a
	 * full day.
	 */
	private static Instant nudgeToPreferredTime(Instant base, LocalTime time) {
		ZonedDateTime baseUtc = base.atZone(ZoneOffset.UTC);
		if (baseUtc.toLocalTime().equals(time)) {
			return base;
		}
		ZonedDateTime nudged = baseUtc.withHour(time.getHour()).withMinute(time.getMinute()).withSecond(0).withNano(0);
		if (nudged.isBefore(baseUtc)) {
			nudged = nudged.plusDays(1);
		}
		return nudged.toInstant();
	}

}
