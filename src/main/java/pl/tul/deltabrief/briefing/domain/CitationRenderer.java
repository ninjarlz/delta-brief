package pl.tul.deltabrief.briefing.domain;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renumbers a briefing's inline {@code [n]} citation markers to match the
 * filtered, ordered list of sources actually cited, and selects those
 * sources out of the briefing's full {@link Briefing#ingestedItems()} list.
 * Extracted out of {@code BriefingController} (which owned this logic
 * privately) so the web view and the emailed copy of a briefing both use
 * one implementation — the emailed citations must match the app's for the
 * same briefing, and duplicating this regex logic risked the two drifting.
 */
public final class CitationRenderer {

	/**
	 * Matches the inline {@code [n]} citation markers the model is instructed
	 * to use (see {@code BriefingPromptBuilder.ANTI_HALLUCINATION_INSTRUCTION}).
	 * Used to show only the sources actually cited in this briefing's text,
	 * not every item that was ingested for it.
	 */
	private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");

	private CitationRenderer() {
	}

	public static RenderedBriefing render(Briefing briefing) {
		List<Integer> citedInOrder = citedNumbersInOrder(briefing);
		Map<Integer, Integer> renumbering = renumbering(citedInOrder);
		return new RenderedBriefing(renumberCitations(briefing.keyChanges(), renumbering),
				renumberCitations(briefing.trendContinuation(), renumbering),
				renumberCitations(briefing.noiseSpeculation(), renumbering),
				renumberCitations(briefing.significance(), renumbering),
				renumberCitations(briefing.uncertainties(), renumbering),
				renumberCitations(briefing.sourceImpact(), renumbering), citedSources(briefing, citedInOrder));
	}

	/**
	 * Only the sources the model actually cited inline (via {@code [n]}) are
	 * shown — every item fetched for this briefing is still persisted (see
	 * {@link Briefing#ingestedItems()}) for traceability, but most of what's
	 * ingested per category is noise relative to a given topic, and showing
	 * all of it undermines the citations' point of grounding each claim in a
	 * specific source. {@code n} is 1-based and matches the numbering
	 * {@code BriefingPromptBuilder} used when it built the prompt — both
	 * derive from the same {@code ingestedItems} list in the same order (see
	 * {@code BriefingService.generateBriefing}).
	 */
	private static List<Integer> citedNumbersInOrder(Briefing briefing) {
		int sourceCount = briefing.ingestedItems().size();
		Set<Integer> citedInOrder = new LinkedHashSet<>();
		for (String section : List.of(briefing.keyChanges(), briefing.trendContinuation(),
				briefing.noiseSpeculation(), briefing.significance(), briefing.uncertainties(),
				briefing.sourceImpact())) {
			Matcher matcher = CITATION_PATTERN.matcher(section);
			while (matcher.find()) {
				citedInOrder.add(Integer.valueOf(matcher.group(1)));
			}
		}
		return citedInOrder.stream().filter(n -> n >= 1 && n <= sourceCount).toList();
	}

	private static List<IngestedItem> citedSources(Briefing briefing, List<Integer> citedInOrder) {
		List<IngestedItem> items = briefing.ingestedItems();
		return citedInOrder.stream().map(n -> items.get(n - 1)).toList();
	}

	/**
	 * The displayed Sources list only ever shows the handful of cited items,
	 * not the full numbered list the prompt was built from — so a citation
	 * like {@code [28]} pointing into a hidden 40-item list would be
	 * meaningless to a reader. Remaps each original citation number to its
	 * 1-based position in the (already-filtered, already-ordered) displayed
	 * list, so the inline markers always match what's actually shown. A
	 * citation with no mapping (shouldn't happen — {@link
	 * #citedNumbersInOrder} already filters to valid, in-range numbers) is
	 * left as-is rather than risk mangling the sentence around it.
	 */
	private static String renumberCitations(String text, Map<Integer, Integer> renumbering) {
		Matcher matcher = CITATION_PATTERN.matcher(text);
		StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			Integer newNumber = renumbering.get(Integer.valueOf(matcher.group(1)));
			String replacement = newNumber != null ? "[" + newNumber + "]" : matcher.group();
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	private static Map<Integer, Integer> renumbering(List<Integer> citedInOrder) {
		Map<Integer, Integer> renumbering = new HashMap<>();
		for (int i = 0; i < citedInOrder.size(); i++) {
			renumbering.put(citedInOrder.get(i), i + 1);
		}
		return renumbering;
	}

	/**
	 * The six renumbered section strings plus the cited sources, in the
	 * order the renumbered citations reference them (1-based).
	 */
	public record RenderedBriefing(String keyChanges, String trendContinuation, String noiseSpeculation,
			String significance, String uncertainties, String sourceImpact, List<IngestedItem> citedSources) {
	}

}
