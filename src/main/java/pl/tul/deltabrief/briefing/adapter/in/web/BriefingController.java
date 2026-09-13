package pl.tul.deltabrief.briefing.adapter.in.web;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
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
	 * redundant query (see impl-review.md F2).
	 */
	private String showBriefing(TopicId topicId, BriefingId briefingId, UserId userId, List<BriefingSummary> history,
			Model model) {
		Optional<Briefing> briefing = briefingService.findOne(topicId, briefingId, userId);
		if (briefing.isEmpty()) {
			return "redirect:/";
		}
		model.addAttribute("topicId", topicId.value());
		model.addAttribute("briefing", toView(briefing.get()));
		model.addAttribute("history", history.stream()
				.map(summary -> toHistoryEntryView(summary, briefingId))
				.toList());
		return "briefing";
	}

	private UserId currentUserId(Authentication authentication) {
		return ((AppUserDetails) authentication.getPrincipal()).userId();
	}

	private static BriefingView toView(Briefing briefing) {
		return new BriefingView(briefing.id().value(), typeLabel(briefing.type()),
				TIMESTAMP_FORMAT.format(briefing.generatedAt()), briefing.keyChanges(), briefing.trendContinuation(),
				briefing.noiseSpeculation(), briefing.significance(), briefing.uncertainties(),
				briefing.sourceImpact(), briefing.ingestedItems().stream().map(BriefingController::toSourceView).toList());
	}

	private static SourceView toSourceView(IngestedItem item) {
		return new SourceView(item.sourceName(), item.title(), item.link());
	}

	private static HistoryEntryView toHistoryEntryView(BriefingSummary summary, BriefingId currentBriefingId) {
		return new HistoryEntryView(summary.id().value(), typeLabel(summary.type()),
				TIMESTAMP_FORMAT.format(summary.generatedAt()), summary.id().equals(currentBriefingId));
	}

	private static String typeLabel(BriefingType type) {
		return type == BriefingType.ONBOARDING ? "Onboarding briefing" : "Delta briefing";
	}

	/**
	 * Display-only shape for {@code briefing.html} — mirrors {@code
	 * TopicController.TopicView}'s pattern of a controller-local record for
	 * template rendering, resolving display labels once here.
	 */
	public record BriefingView(Long id, String typeLabel, String generatedAt, String keyChanges,
			String trendContinuation, String noiseSpeculation, String significance, String uncertainties,
			String sourceImpact, List<SourceView> sources) {
	}

	public record SourceView(String sourceName, String title, String link) {
	}

	public record HistoryEntryView(Long id, String typeLabel, String generatedAt, boolean current) {
	}

}
