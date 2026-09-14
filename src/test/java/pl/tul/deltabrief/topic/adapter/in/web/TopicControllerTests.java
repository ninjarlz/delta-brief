package pl.tul.deltabrief.topic.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.tul.deltabrief.auth.domain.UserId;
import pl.tul.deltabrief.topic.adapter.in.web.TopicController.TopicView;
import pl.tul.deltabrief.topic.domain.CategoryId;
import pl.tul.deltabrief.topic.domain.Frequency;
import pl.tul.deltabrief.topic.domain.Topic;
import pl.tul.deltabrief.topic.domain.TopicId;

class TopicControllerTests {

	private static Topic persisted(Topic topic, long id) {
		topic.assignId(new TopicId(id));
		return topic;
	}

	@Test
	void showsTheNextDueAtAndNoFailureWhenNoScheduledAttemptHappenedYet() {
		Topic topic = persisted(Topic.create(new UserId(1L), "War in Ukraine", new CategoryId(2L), Instant.now()), 10L);
		topic.applySchedule(Frequency.DAILY, null, Instant.parse("2026-09-15T09:00:00Z"));

		TopicView view = TopicController.toView(topic, Map.of(2L, "World News"));

		assertThat(view.id()).isEqualTo(10L);
		assertThat(view.name()).isEqualTo("War in Ukraine");
		assertThat(view.categoryName()).isEqualTo("World News");
		assertThat(view.nextDueAtIso()).isEqualTo("2026-09-15T09:00:00Z");
		assertThat(view.nextDueAt()).contains("2026").contains("UTC");
		assertThat(view.lastRunFailed()).isFalse();
	}

	@Test
	void showsManualWithNoTimestampForAManualTopic() {
		Topic topic = persisted(Topic.create(new UserId(1L), "War in Ukraine", new CategoryId(2L), Instant.now()), 10L);
		topic.applySchedule(Frequency.MANUAL, null, null);

		TopicView view = TopicController.toView(topic, Map.of(2L, "World News"));

		assertThat(view.nextDueAt()).isEqualTo("Manual");
		assertThat(view.nextDueAtIso()).isNull();
	}

	@Test
	void flagsLastRunFailedWhenTheLastScheduledAttemptFailed() {
		Topic topic = persisted(Topic.create(new UserId(1L), "War in Ukraine", new CategoryId(2L), Instant.now()), 10L);
		topic.applySchedule(Frequency.DAILY, null, Instant.parse("2026-09-15T09:00:00Z"));
		topic.recordScheduledFailure(Instant.now());

		TopicView view = TopicController.toView(topic, Map.of(2L, "World News"));

		assertThat(view.lastRunFailed()).isTrue();
	}

	@Test
	void doesNotFlagLastRunFailedAfterASuccessfulScheduledAttempt() {
		Topic topic = persisted(Topic.create(new UserId(1L), "War in Ukraine", new CategoryId(2L), Instant.now()), 10L);
		topic.applySchedule(Frequency.DAILY, null, Instant.parse("2026-09-15T09:00:00Z"));
		topic.recordScheduledSuccess(Instant.now(), Instant.parse("2026-09-16T09:00:00Z"));

		TopicView view = TopicController.toView(topic, Map.of(2L, "World News"));

		assertThat(view.lastRunFailed()).isFalse();
	}

}
