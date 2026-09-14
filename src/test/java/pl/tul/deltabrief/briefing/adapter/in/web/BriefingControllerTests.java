package pl.tul.deltabrief.briefing.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.tul.deltabrief.briefing.adapter.in.web.BriefingController.BriefingView;
import pl.tul.deltabrief.briefing.adapter.in.web.BriefingController.HistoryEntryView;
import pl.tul.deltabrief.briefing.adapter.in.web.BriefingController.SourceView;
import pl.tul.deltabrief.briefing.application.BriefingService.BriefingDetail;
import pl.tul.deltabrief.briefing.application.port.out.BriefingSummary;
import pl.tul.deltabrief.briefing.domain.Briefing;
import pl.tul.deltabrief.briefing.domain.BriefingId;
import pl.tul.deltabrief.briefing.domain.BriefingType;
import pl.tul.deltabrief.briefing.domain.IngestedItem;
import pl.tul.deltabrief.topic.domain.TopicId;

/**
 * The rendered Sources list only ever shows items the model actually cited,
 * not the full numbered list {@code BriefingPromptBuilder} built the prompt
 * from — so an inline {@code [n]} marker referencing a position in that
 * hidden full list would be meaningless to a reader. {@code toView} remaps
 * each citation to its 1-based position in the displayed (filtered,
 * first-appearance-ordered) list instead — see the Javadoc on {@code
 * BriefingController#citedNumbersInOrder}/{@code #renumberCitations}.
 */
class BriefingControllerTests {

	private static IngestedItem item(String title) {
		Instant now = Instant.now();
		return new IngestedItem("Source", title, "https://example.com/" + title, now, now);
	}

	/** {@code toView} reads {@code briefing.id()}, so every fixture here needs one assigned, matching how a real briefing always has an id by the time it reaches the controller (persisted first). */
	private static Briefing persisted(Briefing briefing) {
		briefing.assignId(new BriefingId(1L));
		return briefing;
	}

	@Test
	void renumbersCitationsToMatchTheDisplayedSourceOrder() {
		List<IngestedItem> items = List.of(item("first"), item("second"), item("third"), item("fourth"),
				item("fifth"));
		Briefing briefing = persisted(Briefing.generate(new TopicId(1L), BriefingType.ONBOARDING, Instant.now(),
				"key changes cite [3]", "trend cites [1]", "noise", "significance", "uncertainties", "impact",
				items));
		BriefingDetail detail = new BriefingDetail(briefing, "War in Ukraine");

		BriefingView view = BriefingController.toView(detail, 1);

		assertThat(view.keyChanges()).isEqualTo("key changes cite [1]");
		assertThat(view.trendContinuation()).isEqualTo("trend cites [2]");
		assertThat(view.sources()).extracting(SourceView::title).containsExactly("third", "first");
	}

	@Test
	void dedupesTheSameCitationAppearingInMultipleSections() {
		List<IngestedItem> items = List.of(item("only"));
		Briefing briefing = persisted(Briefing.generate(new TopicId(1L), BriefingType.ONBOARDING, Instant.now(),
				"changes [1]", "trend", "noise [1]", "significance", "uncertainties", "impact", items));
		BriefingDetail detail = new BriefingDetail(briefing, "Topic");

		BriefingView view = BriefingController.toView(detail, 1);

		assertThat(view.sources()).extracting(SourceView::title).containsExactly("only");
		assertThat(view.keyChanges()).isEqualTo("changes [1]");
		assertThat(view.noiseSpeculation()).isEqualTo("noise [1]");
	}

	@Test
	void leavesAnOutOfRangeCitationUntouchedAndOffTheSourcesList() {
		List<IngestedItem> items = List.of(item("only"));
		Briefing briefing = persisted(Briefing.generate(new TopicId(1L), BriefingType.ONBOARDING, Instant.now(),
				"changes [1] and a bogus [99]", "trend", "noise", "significance", "uncertainties", "impact", items));
		BriefingDetail detail = new BriefingDetail(briefing, "Topic");

		BriefingView view = BriefingController.toView(detail, 1);

		assertThat(view.keyChanges()).isEqualTo("changes [1] and a bogus [99]");
		assertThat(view.sources()).extracting(SourceView::title).containsExactly("only");
	}

	@Test
	void showsAnEmptySourceListWhenNothingIsCited() {
		List<IngestedItem> items = List.of(item("only"));
		Briefing briefing = persisted(Briefing.generate(new TopicId(1L), BriefingType.ONBOARDING, Instant.now(),
				"no citation", "trend", "noise", "significance", "uncertainties", "impact", items));
		BriefingDetail detail = new BriefingDetail(briefing, "Topic");

		BriefingView view = BriefingController.toView(detail, 1);

		assertThat(view.sources()).isEmpty();
	}

	@Test
	void buildsTheTitleFromTopicNameAndOrdinal() {
		Briefing briefing = persisted(Briefing.generate(new TopicId(1L), BriefingType.DELTA, Instant.now(), "a", "b",
				"c", "d", "e", "f", List.of()));
		BriefingDetail detail = new BriefingDetail(briefing, "War in Ukraine");

		BriefingView view = BriefingController.toView(detail, 3);

		assertThat(view.title()).isEqualTo("War in Ukraine #3");
	}

	@Test
	void historyEntryTitleMatchesTheTopicNameAndOrdinalFormat() {
		BriefingSummary summary = new BriefingSummary(new BriefingId(1L), BriefingType.DELTA, Instant.now());

		HistoryEntryView view = BriefingController.toHistoryEntryView(summary, new BriefingId(2L), "War in Ukraine",
				3);

		assertThat(view.title()).isEqualTo("War in Ukraine #3");
		assertThat(view.current()).isFalse();
	}

	@Test
	void historyEntryTitleAppendsOnboardingBriefingForTheOnboardingType() {
		BriefingSummary summary = new BriefingSummary(new BriefingId(1L), BriefingType.ONBOARDING, Instant.now());

		HistoryEntryView view = BriefingController.toHistoryEntryView(summary, new BriefingId(1L), "War in Ukraine",
				1);

		assertThat(view.title()).isEqualTo("War in Ukraine #1 (onboarding briefing)");
		assertThat(view.current()).isTrue();
	}

}
