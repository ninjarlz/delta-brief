package pl.tul.deltabrief.briefing.adapter.in.web;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
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
import pl.tul.deltabrief.briefing.application.BriefingService.TopicHistory;
import pl.tul.deltabrief.briefing.application.BriefingService.TopicNotFoundException;
import pl.tul.deltabrief.briefing.application.port.out.BriefingContentGenerator.GenerationFailedException;
import pl.tul.deltabrief.briefing.application.port.out.BriefingSummary;
import pl.tul.deltabrief.briefing.domain.Briefing;
import pl.tul.deltabrief.briefing.domain.BriefingId;
import pl.tul.deltabrief.briefing.domain.BriefingType;
import pl.tul.deltabrief.briefing.domain.CitationRenderer;
import pl.tul.deltabrief.briefing.domain.CitationRenderer.RenderedBriefing;
import pl.tul.deltabrief.briefing.domain.IngestedItem;
import pl.tul.deltabrief.topic.domain.TopicId;

@Controller
@RequiredArgsConstructor
public class BriefingController {

	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm 'UTC'")
			.withZone(ZoneOffset.UTC);

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
	 * Reachable independent of generating or viewing any specific briefing —
	 * unlike {@link #latest}, a topic with zero briefings yet still renders
	 * (with an empty state) rather than redirecting to {@code /}, since an
	 * owned topic with no history is a valid state here, not an error.
	 */
	@GetMapping("/topics/{topicId}/briefings")
	public String history(@PathVariable Long topicId, Authentication authentication, Model model) {
		TopicId id = new TopicId(topicId);
		Optional<TopicHistory> history = briefingService.getHistory(id, currentUserId(authentication));
		if (history.isEmpty()) {
			return "redirect:/";
		}
		List<BriefingSummary> summaries = history.get().summaries();
		model.addAttribute("topicId", topicId);
		model.addAttribute("topicName", history.get().topicName());
		model.addAttribute("history", IntStream.range(0, summaries.size())
				.mapToObj(i -> toHistoryEntryView(summaries.get(i), null, history.get().topicName(),
						summaries.size() - i))
				.toList());
		return "briefing-history";
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
		String topicName = detail.get().topicName();
		model.addAttribute("topicId", topicId.value());
		model.addAttribute("briefing", toView(detail.get(), ordinal(history, briefingId)));
		model.addAttribute("history", IntStream.range(0, history.size())
				.mapToObj(i -> toHistoryEntryView(history.get(i), briefingId, topicName, history.size() - i))
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
		RenderedBriefing rendered = CitationRenderer.render(briefing);
		List<SourceView> sources = rendered.citedSources().stream().map(BriefingController::toSourceView).toList();
		return new BriefingView(briefing.id().value(), "%s #%d".formatted(detail.topicName(), ordinal),
				typeLabel(briefing.type()), TIMESTAMP_FORMAT.format(briefing.generatedAt()),
				briefing.generatedAt().toString(), rendered.keyChanges(), rendered.trendContinuation(),
				rendered.noiseSpeculation(), rendered.significance(), rendered.uncertainties(),
				rendered.sourceImpact(), sources);
	}

	private static SourceView toSourceView(IngestedItem item) {
		return new SourceView(item.sourceName(), item.title(), item.link());
	}

	/**
	 * History entries use the same "{topic name} #{ordinal}" naming as the
	 * main page title, for consistency — the onboarding briefing (always
	 * ordinal 1) additionally gets "(onboarding briefing)" appended, since
	 * it's the one entry that isn't self-evidently a delta from something
	 * else in the list.
	 */
	static HistoryEntryView toHistoryEntryView(BriefingSummary summary, BriefingId currentBriefingId,
			String topicName, int ordinal) {
		String title = "%s #%d".formatted(topicName, ordinal);
		if (summary.type() == BriefingType.ONBOARDING) {
			title += " (onboarding briefing)";
		}
		return new HistoryEntryView(summary.id().value(), title, TIMESTAMP_FORMAT.format(summary.generatedAt()),
				summary.generatedAt().toString(), summary.id().equals(currentBriefingId));
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

	public record HistoryEntryView(Long id, String title, String generatedAt, String generatedAtIso, boolean current) {
	}

}
