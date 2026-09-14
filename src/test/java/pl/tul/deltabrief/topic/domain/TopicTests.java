package pl.tul.deltabrief.topic.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import pl.tul.deltabrief.auth.domain.UserId;

class TopicTests {

	@Test
	void createAssignsAllFieldsWithNoIdYet() {
		UserId userId = new UserId(1L);
		CategoryId categoryId = new CategoryId(2L);
		Instant now = Instant.now();

		Topic topic = Topic.create(userId, "War in Ukraine", categoryId, now);

		assertThat(topic.id()).isNull();
		assertThat(topic.userId()).isEqualTo(userId);
		assertThat(topic.name()).isEqualTo("War in Ukraine");
		assertThat(topic.categoryId()).isEqualTo(categoryId);
		assertThat(topic.description()).isNull();
		assertThat(topic.createdAt()).isEqualTo(now);
		assertThat(topic.frequency()).isEqualTo(Frequency.DAILY);
		assertThat(topic.preferredHour()).isNull();
		assertThat(topic.nextDueAt()).isNull();
		assertThat(topic.lastScheduledAttemptAt()).isNull();
		assertThat(topic.lastScheduledStatus()).isNull();
		assertThat(topic.emailEnabled()).isTrue();
	}

	@Test
	void createWithADescriptionAssignsIt() {
		Topic topic = Topic.create(new UserId(1L), "War in Ukraine", new CategoryId(2L),
				"Tracking the humanitarian angle", Instant.now());

		assertThat(topic.description()).isEqualTo("Tracking the humanitarian angle");
	}

	@Test
	void assignIdSetsTheId() {
		Topic topic = Topic.create(new UserId(1L), "War in Ukraine", new CategoryId(2L), Instant.now());

		topic.assignId(new TopicId(42L));

		assertThat(topic.id()).isEqualTo(new TopicId(42L));
	}

	@Test
	void applyScheduleSetsFrequencyPreferredHourEmailEnabledAndNextDueAt() {
		Topic topic = Topic.create(new UserId(1L), "War in Ukraine", new CategoryId(2L), Instant.now());
		Instant nextDueAt = Instant.parse("2026-09-20T09:00:00Z");

		topic.applySchedule(Frequency.WEEKLY, 9, false, nextDueAt);

		assertThat(topic.frequency()).isEqualTo(Frequency.WEEKLY);
		assertThat(topic.preferredHour()).isEqualTo(9);
		assertThat(topic.emailEnabled()).isFalse();
		assertThat(topic.nextDueAt()).isEqualTo(nextDueAt);
	}

	@Test
	void recordScheduledSuccessAdvancesNextDueAtAndMarksSuccess() {
		Topic topic = Topic.create(new UserId(1L), "War in Ukraine", new CategoryId(2L), Instant.now());
		Instant attemptedAt = Instant.parse("2026-09-14T09:00:00Z");
		Instant nextDueAt = Instant.parse("2026-09-15T09:00:00Z");

		topic.recordScheduledSuccess(attemptedAt, nextDueAt);

		assertThat(topic.lastScheduledAttemptAt()).isEqualTo(attemptedAt);
		assertThat(topic.lastScheduledStatus()).isEqualTo(ScheduledRunStatus.SUCCESS);
		assertThat(topic.nextDueAt()).isEqualTo(nextDueAt);
	}

	@Test
	void recordScheduledFailureLeavesNextDueAtUnchangedAndMarksFailure() {
		Topic topic = Topic.create(new UserId(1L), "War in Ukraine", new CategoryId(2L), Instant.now());
		Instant originalNextDueAt = Instant.parse("2026-09-15T09:00:00Z");
		topic.applySchedule(Frequency.DAILY, 9, true, originalNextDueAt);
		Instant attemptedAt = Instant.parse("2026-09-15T09:05:00Z");

		topic.recordScheduledFailure(attemptedAt);

		assertThat(topic.lastScheduledAttemptAt()).isEqualTo(attemptedAt);
		assertThat(topic.lastScheduledStatus()).isEqualTo(ScheduledRunStatus.FAILURE);
		assertThat(topic.nextDueAt()).isEqualTo(originalNextDueAt);
	}

}
