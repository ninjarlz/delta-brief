package pl.tul.deltabrief.briefing.adapter.in.web;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import pl.tul.deltabrief.auth.adapter.out.security.AppUserDetails;
import pl.tul.deltabrief.auth.domain.UserId;
import pl.tul.deltabrief.briefing.application.BriefingService;
import pl.tul.deltabrief.briefing.application.BriefingService.BriefingDetail;
import pl.tul.deltabrief.briefing.application.BriefingService.TopicNotFoundException;
import pl.tul.deltabrief.briefing.application.port.out.BriefingContentGenerator.GenerationFailedException;
import pl.tul.deltabrief.briefing.application.port.out.BriefingSummary;
import pl.tul.deltabrief.briefing.domain.Briefing;
import pl.tul.deltabrief.briefing.domain.BriefingId;
import pl.tul.deltabrief.briefing.domain.BriefingType;
import pl.tul.deltabrief.briefing.domain.IngestedItem;
import pl.tul.deltabrief.topic.domain.TopicId;

@Controller
@RequiredArgsConstructor
public class BriefingController {

	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm 'UTC'")
			.withZone(ZoneOffset.UTC);

	/**
	 * Matches the inline {@code [n]} citation markers the model is instructed
	 * to use (see {@code BriefingPromptBuilder.ANTI_HALLUCINATION_INSTRUCTION}).
	 * Used to show only the sources actually cited in this briefing's text,
	 * not every item that was ingested for it.
	 */
	private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");

	private final BriefingService briefingService;

	/**
	 * Synchronous generation (per plan.md's chosen UX) — the whole
	 * ingest+generate+persist flow runs within this request.
	 */
	@PostMapping("/topics/{topicId}/briefings")
	public String generate(@PathVariable Long topicId, Authentication authentication, Model model) {
		TopicId id = new TopicId(topicId);
		try {
			briefingService.generateBriefing(id, currentUserId(authentication));
		} catch (TopicNotFoundException notFound) {
			return "redirect:/";
		} catch (GenerationFailedException failed) {
			model.addAttribute("topicId", topicId);
			return "briefing-generation-failed";
		}
		return "redirect:/topics/%d/briefings/latest".formatted(topicId);
	}

	@GetMapping("/topics/{topicId}/briefings/latest")
	public String latest(@PathVariable Long topicId, Authentication authentication, Model model) {
		TopicId id = new TopicId(topicId);
		UserId userId = currentUserId(authentication);
		List<BriefingSummary> summaries = briefingService.listSummaries(id, userId);
		if (summaries.isEmpty()) {
			return "redirect:/";
		}
		return showBriefing(id, summaries.get(0).id(), userId, summaries, model);
	}

	@GetMapping("/topics/{topicId}/briefings/{briefingId}")
	public String show(@PathVariable Long topicId, @PathVariable Long briefingId, Authentication authentication,
			Model model) {
		TopicId id = new TopicId(topicId);
		UserId userId = currentUserId(authentication);
		return showBriefing(id, new BriefingId(briefingId), userId, briefingService.listSummaries(id, userId), model);
	}

	/**
	 * Always redirects to {@code /} when the topic isn't owned or the
	 * briefing isn't found/doesn't belong to the topic — never a
	 * distinguishable 404, mirroring {@code TopicController.deleteTopic}'s
	 * ownership-leak-avoidance convention. {@code history} is passed in
	 * rather than re-fetched here — {@link #latest} already has it in hand
	 * from its own ownership-scoped lookup, so re-deriving it would be a
	 * redundant query (see impl-review.md F2). It's also reused to compute
	 * this briefing's ordinal (its position among the topic's own briefings,
	 * oldest = #1) for the page title, since {@code history} is already
	 * ordered newest-first.
	 */
	private String showBriefing(TopicId topicId, BriefingId briefingId, UserId userId, List<BriefingSummary> history,
			Model model) {
		Optional<BriefingDetail> detail = briefingService.findOne(topicId, briefingId, userId);
		if (detail.isEmpty()) {
			return "redirect:/";
		}
		model.addAttribute("topicId", topicId.value());
		model.addAttribute("briefing", toView(detail.get(), ordinal(history, briefingId)));
		model.addAttribute("history", history.stream()
				.map(summary -> toHistoryEntryView(summary, briefingId))
				.toList());
		return "briefing";
	}

	private UserId currentUserId(Authentication authentication) {
		return ((AppUserDetails) authentication.getPrincipal()).userId();
	}

	private static int ordinal(List<BriefingSummary> newestFirstHistory, BriefingId briefingId) {
		int descendingIndex = newestFirstHistory.stream().map(BriefingSummary::id).toList().indexOf(briefingId);
		return newestFirstHistory.size() - descendingIndex;
	}

	static BriefingView toView(BriefingDetail detail, int ordinal) {
		Briefing briefing = detail.briefing();
		List<Integer> citedInOrder = citedNumbersInOrder(briefing);
		Map<Integer, Integer> renumbering = renumbering(citedInOrder);
		return new BriefingView(briefing.id().value(), "%s #%d".formatted(detail.topicName(), ordinal),
				typeLabel(briefing.type()), TIMESTAMP_FORMAT.format(briefing.generatedAt()),
				briefing.generatedAt().toString(), renumberCitations(briefing.keyChanges(), renumbering),
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

	private static List<SourceView> citedSources(Briefing briefing, List<Integer> citedInOrder) {
		List<IngestedItem> items = briefing.ingestedItems();
		return citedInOrder.stream().map(n -> toSourceView(items.get(n - 1))).toList();
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

	private static SourceView toSourceView(IngestedItem item) {
		return new SourceView(item.sourceName(), item.title(), item.link());
	}

	private static HistoryEntryView toHistoryEntryView(BriefingSummary summary, BriefingId currentBriefingId) {
		return new HistoryEntryView(summary.id().value(), typeLabel(summary.type()),
				TIMESTAMP_FORMAT.format(summary.generatedAt()), summary.generatedAt().toString(),
				summary.id().equals(currentBriefingId));
	}

	private static String typeLabel(BriefingType type) {
		return type == BriefingType.ONBOARDING ? "Onboarding briefing" : "Delta briefing";
	}

	/**
	 * Display-only shape for {@code briefing.html} — mirrors {@code
	 * TopicController.TopicView}'s pattern of a controller-local record for
	 * template rendering, resolving display labels once here. {@code title}
	 * is "{topic name} #{ordinal}" (e.g. "War in Ukraine #3"); {@code
	 * typeLabel} (Onboarding/Delta) moved out of the page heading and into
	 * the subtitle alongside the timestamp. {@code generatedAtIso} carries
	 * the raw instant for {@code app.js} to re-render in the viewer's local
	 * timezone; {@code generatedAt} (UTC) stays as the no-JS fallback.
	 */
	public record BriefingView(Long id, String title, String typeLabel, String generatedAt, String generatedAtIso,
			String keyChanges, String trendContinuation, String noiseSpeculation, String significance,
			String uncertainties, String sourceImpact, List<SourceView> sources) {
	}

	public record SourceView(String sourceName, String title, String link) {
	}

	public record HistoryEntryView(Long id, String typeLabel, String generatedAt, String generatedAtIso,
			boolean current) {
	}

}
