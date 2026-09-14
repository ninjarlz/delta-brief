package pl.tul.deltabrief.topic.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ScheduleCalculatorTests {

	private static final Instant ANCHOR = Instant.parse("2026-09-14T10:00:00Z");

	@Test
	void manualFrequencyHasNoSchedule() {
		assertThat(ScheduleCalculator.nextDueAt(ANCHOR, Frequency.MANUAL, null)).isNull();
		assertThat(ScheduleCalculator.nextDueAt(ANCHOR, Frequency.MANUAL, 9)).isNull();
	}

	@Test
	void twiceDailyAddsTwelveHoursWithNoPreferredHour() {
		Instant nextDueAt = ScheduleCalculator.nextDueAt(ANCHOR, Frequency.TWICE_DAILY, null);

		assertThat(nextDueAt).isEqualTo(ANCHOR.plus(Duration.ofHours(12)));
	}

	@Test
	void dailyAddsOneDayWithNoPreferredHour() {
		Instant nextDueAt = ScheduleCalculator.nextDueAt(ANCHOR, Frequency.DAILY, null);

		assertThat(nextDueAt).isEqualTo(ANCHOR.plus(Duration.ofDays(1)));
	}

	@Test
	void everyOtherDayAddsTwoDaysWithNoPreferredHour() {
		Instant nextDueAt = ScheduleCalculator.nextDueAt(ANCHOR, Frequency.EVERY_OTHER_DAY, null);

		assertThat(nextDueAt).isEqualTo(ANCHOR.plus(Duration.ofDays(2)));
	}

	@Test
	void weeklyAddsSevenDaysWithNoPreferredHour() {
		Instant nextDueAt = ScheduleCalculator.nextDueAt(ANCHOR, Frequency.WEEKLY, null);

		assertThat(nextDueAt).isEqualTo(ANCHOR.plus(Duration.ofDays(7)));
	}

	@Test
	void preferredHourAheadOfBaseHourNudgesForwardOnTheSameDay() {
		// ANCHOR + 1 day = 2026-09-15T10:00:00Z; preferred hour 14 is later
		// the same day.
		Instant nextDueAt = ScheduleCalculator.nextDueAt(ANCHOR, Frequency.DAILY, 14);

		assertThat(nextDueAt).isEqualTo(Instant.parse("2026-09-15T14:00:00Z"));
	}

	@Test
	void preferredHourAlreadyPassedRollsToTheNextDay() {
		// ANCHOR + 1 day = 2026-09-15T10:00:00Z; preferred hour 5 has
		// already passed that day, so it rolls to the following day.
		Instant nextDueAt = ScheduleCalculator.nextDueAt(ANCHOR, Frequency.DAILY, 5);

		assertThat(nextDueAt).isEqualTo(Instant.parse("2026-09-16T05:00:00Z"));
	}

	@Test
	void preferredHourEqualToBaseHourReturnsBaseUnchanged() {
		// ANCHOR + 1 day = 2026-09-15T10:00:00Z already falls within hour
		// 10 — must NOT be rounded down to 10:00:00 (which would be before
		// the base) nor rolled to the next day.
		Instant nextDueAt = ScheduleCalculator.nextDueAt(ANCHOR, Frequency.DAILY, 10);

		assertThat(nextDueAt).isEqualTo(ANCHOR.plus(Duration.ofDays(1)));
	}

}
