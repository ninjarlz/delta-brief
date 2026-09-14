package pl.tul.deltabrief.briefing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.tul.deltabrief.briefing.domain.CitationRenderer.RenderedBriefing;
import pl.tul.deltabrief.topic.domain.TopicId;

/**
 * Direct unit coverage for the extracted renumbering logic — mirrors the
 * end-to-end assertions {@code BriefingControllerTests} already makes
 * through {@code toView}, but exercises {@link CitationRenderer} itself so
 * the future emailed-briefing path (which also calls this class) has its
 * own independent proof the renumbering is correct.
 */
class CitationRendererTests {

	private static IngestedItem item(String title) {
		Instant now = Instant.now();
		return new IngestedItem("Source", title, "https://example.com/" + title, now, now);
	}

	@Test
	void renumbersCitationsToMatchTheOrderTheyFirstAppear() {
		List<IngestedItem> items = List.of(item("first"), item("second"), item("third"), item("fourth"),
				item("fifth"));
		Briefing briefing = Briefing.generate(new TopicId(1L), BriefingType.ONBOARDING, Instant.now(),
				"key changes cite [3]", "trend cites [1]", "noise", "significance", "uncertainties", "impact", items);

		RenderedBriefing rendered = CitationRenderer.render(briefing);

		assertThat(rendered.keyChanges()).isEqualTo("key changes cite [1]");
		assertThat(rendered.trendContinuation()).isEqualTo("trend cites [2]");
		assertThat(rendered.citedSources()).extracting(IngestedItem::title).containsExactly("third", "first");
	}

	@Test
	void dedupesTheSameCitationAppearingInMultipleSections() {
		List<IngestedItem> items = List.of(item("only"));
		Briefing briefing = Briefing.generate(new TopicId(1L), BriefingType.ONBOARDING, Instant.now(), "changes [1]",
				"trend", "noise [1]", "significance", "uncertainties", "impact", items);

		RenderedBriefing rendered = CitationRenderer.render(briefing);

		assertThat(rendered.citedSources()).extracting(IngestedItem::title).containsExactly("only");
		assertThat(rendered.keyChanges()).isEqualTo("changes [1]");
		assertThat(rendered.noiseSpeculation()).isEqualTo("noise [1]");
	}

	@Test
	void leavesAnOutOfRangeCitationUntouchedAndOffTheSourcesList() {
		List<IngestedItem> items = List.of(item("only"));
		Briefing briefing = Briefing.generate(new TopicId(1L), BriefingType.ONBOARDING, Instant.now(),
				"changes [1] and a bogus [99]", "trend", "noise", "significance", "uncertainties", "impact", items);

		RenderedBriefing rendered = CitationRenderer.render(briefing);

		assertThat(rendered.keyChanges()).isEqualTo("changes [1] and a bogus [99]");
		assertThat(rendered.citedSources()).extracting(IngestedItem::title).containsExactly("only");
	}

	@Test
	void returnsNoCitedSourcesWhenNothingIsCited() {
		List<IngestedItem> items = List.of(item("only"));
		Briefing briefing = Briefing.generate(new TopicId(1L), BriefingType.ONBOARDING, Instant.now(), "no citation",
				"trend", "noise", "significance", "uncertainties", "impact", items);

		RenderedBriefing rendered = CitationRenderer.render(briefing);

		assertThat(rendered.citedSources()).isEmpty();
	}

}
