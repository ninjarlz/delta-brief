package pl.tul.deltabrief.topic.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Pure computation of a topic's next scheduled-generation instant. No Spring
 * dependency, no wall-clock read — {@code anchor} (the last successful
 * generation's {@code generatedAt}, or {@code createdAt} when there is none
 * yet) is always passed in, so this is directly unit-testable.
 */
public final class ScheduleCalculator {

	private ScheduleCalculator() {
	}

	/**
	 * @param anchor the timestamp the interval is measured from
	 * @param frequency how often to regenerate; {@link Frequency#MANUAL} has
	 * no schedule
	 * @param preferredHour optional UTC hour-of-day (0-23) to nudge the
	 * result toward; {@code null} means no nudging. The app has no per-user
	 * timezone concept, so this is interpreted in UTC.
	 * @return the next due instant, nudged forward (never earlier) to
	 * {@code preferredHour} when set; {@code null} for {@link Frequency#MANUAL}
	 */
	public static Instant nextDueAt(Instant anchor, Frequency frequency, Integer preferredHour) {
		if (frequency == Frequency.MANUAL) {
			return null;
		}
		Instant base = anchor.plus(frequency.interval());
		return preferredHour == null ? base : nudgeToPreferredHour(base, preferredHour);
	}

	/**
	 * Rounds {@code base} forward to the next UTC instant at
	 * {@code preferredHour}:00 — never earlier than {@code base}. If
	 * {@code base} already falls within {@code preferredHour}, it is
	 * returned unchanged rather than rounded down to that hour's :00 mark
	 * (which could otherwise land before {@code base} and force an
	 * unwanted extra day of rollover).
	 */
	private static Instant nudgeToPreferredHour(Instant base, int preferredHour) {
		ZonedDateTime baseUtc = base.atZone(ZoneOffset.UTC);
		if (baseUtc.getHour() == preferredHour) {
			return base;
		}
		ZonedDateTime nudged = baseUtc.withHour(preferredHour).withMinute(0).withSecond(0).withNano(0);
		if (nudged.isBefore(baseUtc)) {
			nudged = nudged.plusDays(1);
		}
		return nudged.toInstant();
	}

}
