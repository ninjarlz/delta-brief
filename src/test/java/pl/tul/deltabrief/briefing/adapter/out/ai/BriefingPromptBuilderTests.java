package pl.tul.deltabrief.briefing.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.tul.deltabrief.briefing.application.port.out.BriefingContentGenerator.GenerationRequest;
import pl.tul.deltabrief.briefing.application.port.out.BriefingContentGenerator.PreviousBriefing;
import pl.tul.deltabrief.briefing.domain.BriefingType;
import pl.tul.deltabrief.briefing.domain.IngestedItem;

/**
 * Asserts the anti-hallucination instruction and the numbered source list
 * are always present in the constructed prompt, for both {@link
 * BriefingType} values — see plan.md's Phase 3 automated success criteria.
 */
class BriefingPromptBuilderTests {

	private final BriefingPromptBuilder promptBuilder = new BriefingPromptBuilder();

	private static List<IngestedItem> oneItem() {
		Instant now = Instant.now();
		return List.of(new IngestedItem("BBC News", "Headline", "https://example.com/1", now, now));
	}

	@Test
	void onboardingPromptContainsGuardrailAndSources() {
		GenerationRequest request = new GenerationRequest("War in Ukraine", null, "World News",
				BriefingType.ONBOARDING, null, oneItem());

		String prompt = promptBuilder.build(request);

		assertThat(prompt).contains(BriefingPromptBuilder.ANTI_HALLUCINATION_INSTRUCTION);
		assertThat(prompt).contains(BriefingPromptBuilder.SOURCE_RELEVANCE_AND_PRIORITY_GUIDANCE);
		assertThat(prompt).contains(BriefingPromptBuilder.INJECTION_GUARDRAIL);
		assertThat(prompt).contains(BriefingPromptBuilder.NUMBERED_SOURCES_HEADER);
		assertThat(prompt).contains("[1] BBC News: Headline — https://example.com/1");
		assertThat(prompt).contains("no prior briefing to compare against");
		assertThat(prompt).contains("\"\"\"War in Ukraine\"\"\"");
	}

	@Test
	void deltaPromptContainsGuardrailAndSourcesAndBaseline() {
		PreviousBriefing previous = new PreviousBriefing("prior key changes", "prior trend", "prior noise",
				"prior significance", "prior uncertainties", "prior source impact");
		GenerationRequest request = new GenerationRequest("War in Ukraine", null, "World News", BriefingType.DELTA,
				previous, oneItem());

		String prompt = promptBuilder.build(request);

		assertThat(prompt).contains(BriefingPromptBuilder.ANTI_HALLUCINATION_INSTRUCTION);
		assertThat(prompt).contains(BriefingPromptBuilder.SOURCE_RELEVANCE_AND_PRIORITY_GUIDANCE);
		assertThat(prompt).contains(BriefingPromptBuilder.INJECTION_GUARDRAIL);
		assertThat(prompt).contains(BriefingPromptBuilder.NUMBERED_SOURCES_HEADER);
		assertThat(prompt).contains(BriefingPromptBuilder.DELTA_COMPARISON_INSTRUCTION);
		assertThat(prompt).contains("[1] BBC News: Headline — https://example.com/1");
		assertThat(prompt).contains("prior key changes");
		assertThat(prompt).contains("prior source impact");
		assertThat(prompt).contains("\"\"\"War in Ukraine\"\"\"");
	}

	@Test
	void numberedSourceEntriesIncludeTheSourceNameSoTheModelCanTellCuratedFromGoogleNews() {
		Instant now = Instant.now();
		List<IngestedItem> items = List.of(
				new IngestedItem("BBC News – World", "Curated headline", "https://example.com/curated", now, now),
				new IngestedItem("Google News: War in Ukraine", "Search headline", "https://example.com/search",
						now, now));
		GenerationRequest request = new GenerationRequest("War in Ukraine", null, "World News",
				BriefingType.ONBOARDING, null, items);

		String prompt = promptBuilder.build(request);

		assertThat(prompt).contains("[1] BBC News – World: Curated headline — https://example.com/curated");
		assertThat(prompt).contains("[2] Google News: War in Ukraine: Search headline — https://example.com/search");
	}

	@Test
	void includesTheTopicDescriptionDelimitedWhenPresent() {
		GenerationRequest request = new GenerationRequest("War in Ukraine", "I have family there — humanitarian "
				+ "angle matters more to me than politics.", "World News", BriefingType.ONBOARDING, null, oneItem());

		String prompt = promptBuilder.build(request);

		assertThat(prompt)
				.contains("\"\"\"I have family there — humanitarian angle matters more to me than politics.\"\"\"");
	}

	@Test
	void omitsTheDescriptionBlockEntirelyWhenNotProvided() {
		GenerationRequest request = new GenerationRequest("War in Ukraine", null, "World News",
				BriefingType.ONBOARDING, null, oneItem());

		String prompt = promptBuilder.build(request);

		assertThat(prompt).doesNotContain("Topic description");
	}

}
