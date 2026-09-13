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
		assertThat(topic.createdAt()).isEqualTo(now);
	}

	@Test
	void assignIdSetsTheId() {
		Topic topic = Topic.create(new UserId(1L), "War in Ukraine", new CategoryId(2L), Instant.now());

		topic.assignId(new TopicId(42L));

		assertThat(topic.id()).isEqualTo(new TopicId(42L));
	}

}
