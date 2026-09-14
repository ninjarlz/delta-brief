package pl.tul.deltabrief.topic.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class ScheduleCalculatorTests {

	private static final Instant ANCHOR = Instant.parse("2026-09-14T10:00:00Z");

	@Test
	void dailyAddsOneDayThenNudgesToTheDefaultNineUtcTime() {
		// ANCHOR + 1 day = 2026-09-15T10:00:00Z; the default 09:00 has
		// already passed that day, so it rolls to the following day.
		Instant nextDueAt = ScheduleCalculator.nextDueAt(ANCHOR, Frequency.DAILY, null);

		assertThat(nextDueAt).isEqualTo(Instant.parse("2026-09-16T09:00:00Z"));
	}

	@Test
	void everyOtherDayAddsTwoDaysThenNudgesToTheDefaultNineUtcTime() {
		// ANCHOR + 2 days = 2026-09-16T10:00:00Z; same rollover reasoning.
		Instant nextDueAt = ScheduleCalculator.nextDueAt(ANCHOR, Frequency.EVERY_OTHER_DAY, null);

		assertThat(nextDueAt).isEqualTo(Instant.parse("2026-09-17T09:00:00Z"));
	}

	@Test
	void weeklyAddsSevenDaysThenNudgesToTheDefaultNineUtcTime() {
		// ANCHOR + 7 days = 2026-09-21T10:00:00Z; same rollover reasoning.
		Instant nextDueAt = ScheduleCalculator.nextDueAt(ANCHOR, Frequency.WEEKLY, null);

		assertThat(nextDueAt).isEqualTo(Instant.parse("2026-09-22T09:00:00Z"));
	}

	@Test
	void nullPreferredTimeIsEquivalentToExplicitlyPassingTheDefault() {
		Instant withNull = ScheduleCalculator.nextDueAt(ANCHOR, Frequency.DAILY, null);
		Instant withExplicitDefault = ScheduleCalculator.nextDueAt(ANCHOR, Frequency.DAILY, LocalTime.of(9, 0));

		assertThat(withNull).isEqualTo(withExplicitDefault);
	}

	@Test
	void explicitPreferredTimeWithNonZeroMinutesRoundTripsExactly() {
		// ANCHOR + 1 day = 2026-09-15T10:00:00Z; 14:37 is later the same day.
		Instant nextDueAt = ScheduleCalculator.nextDueAt(ANCHOR, Frequency.DAILY, LocalTime.of(14, 37));

		assertThat(nextDueAt).isEqualTo(Instant.parse("2026-09-15T14:37:00Z"));
	}

	@Test
	void preferredTimeAheadOfBaseTimeNudgesForwardOnTheSameDay() {
		// ANCHOR + 1 day = 2026-09-15T10:00:00Z; preferred time 14:00 is
		// later the same day.
		Instant nextDueAt = ScheduleCalculator.nextDueAt(ANCHOR, Frequency.DAILY, LocalTime.of(14, 0));

		assertThat(nextDueAt).isEqualTo(Instant.parse("2026-09-15T14:00:00Z"));
	}

	@Test
	void preferredTimeAlreadyPassedRollsToTheNextDay() {
		// ANCHOR + 1 day = 2026-09-15T10:00:00Z; preferred time 05:00 has
		// already passed that day, so it rolls to the following day.
		Instant nextDueAt = ScheduleCalculator.nextDueAt(ANCHOR, Frequency.DAILY, LocalTime.of(5, 0));

		assertThat(nextDueAt).isEqualTo(Instant.parse("2026-09-16T05:00:00Z"));
	}

	@Test
	void preferredTimeEqualToBaseTimeReturnsBaseUnchanged() {
		// ANCHOR + 1 day = 2026-09-15T10:00:00Z already falls exactly on
		// 10:00 — must NOT be rolled to the next day.
		Instant nextDueAt = ScheduleCalculator.nextDueAt(ANCHOR, Frequency.DAILY, LocalTime.of(10, 0));

		assertThat(nextDueAt).isEqualTo(ANCHOR.plus(Duration.ofDays(1)));
	}

}
