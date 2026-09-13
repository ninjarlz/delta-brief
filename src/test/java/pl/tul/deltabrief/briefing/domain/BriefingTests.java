package pl.tul.deltabrief.briefing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.tul.deltabrief.topic.domain.TopicId;

class BriefingTests {

	@Test
	void generateAssignsAllFieldsWithNoIdYet() {
		TopicId topicId = new TopicId(1L);
		Instant now = Instant.now();
		IngestedItem item = new IngestedItem("BBC News", "Headline", "https://example.com/1", now, now);

		Briefing briefing = Briefing.generate(topicId, BriefingType.ONBOARDING, now, "key changes",
				"trend continuation", "noise", "significance", "uncertainties", "source impact", List.of(item));

		assertThat(briefing.id()).isNull();
		assertThat(briefing.topicId()).isEqualTo(topicId);
		assertThat(briefing.type()).isEqualTo(BriefingType.ONBOARDING);
		assertThat(briefing.generatedAt()).isEqualTo(now);
		assertThat(briefing.keyChanges()).isEqualTo("key changes");
		assertThat(briefing.trendContinuation()).isEqualTo("trend continuation");
		assertThat(briefing.noiseSpeculation()).isEqualTo("noise");
		assertThat(briefing.significance()).isEqualTo("significance");
		assertThat(briefing.uncertainties()).isEqualTo("uncertainties");
		assertThat(briefing.sourceImpact()).isEqualTo("source impact");
		assertThat(briefing.ingestedItems()).containsExactly(item);
	}

	@Test
	void assignIdSetsTheId() {
		Briefing briefing = Briefing.generate(new TopicId(1L), BriefingType.DELTA, Instant.now(), "a", "b", "c", "d",
				"e", "f", List.of());

		briefing.assignId(new BriefingId(42L));

		assertThat(briefing.id()).isEqualTo(new BriefingId(42L));
	}

	@Test
	void ingestedItemsIsImmutable() {
		Instant now = Instant.now();
		List<IngestedItem> mutable = new java.util.ArrayList<>();
		mutable.add(new IngestedItem("BBC News", "Headline", "https://example.com/1", now, now));
		Briefing briefing = Briefing.generate(new TopicId(1L), BriefingType.ONBOARDING, now, "a", "b", "c", "d", "e",
				"f", mutable);

		mutable.clear();

		assertThat(briefing.ingestedItems()).hasSize(1);
	}

}
