package pl.tul.deltabrief.briefing.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pl.tul.deltabrief.auth.domain.UserId;
import pl.tul.deltabrief.briefing.application.port.out.BriefingContentGenerator;
import pl.tul.deltabrief.briefing.application.port.out.BriefingContentGenerator.GeneratedBriefingContent;
import pl.tul.deltabrief.briefing.application.port.out.BriefingContentGenerator.GenerationRequest;
import pl.tul.deltabrief.briefing.application.port.out.BriefingContentGenerator.PreviousBriefing;
import pl.tul.deltabrief.briefing.application.port.out.BriefingRepository;
import pl.tul.deltabrief.briefing.application.port.out.BriefingSummary;
import pl.tul.deltabrief.briefing.application.port.out.FeedSource;
import pl.tul.deltabrief.briefing.application.port.out.FeedSourceCatalog;
import pl.tul.deltabrief.briefing.application.port.out.FetchedItem;
import pl.tul.deltabrief.briefing.application.port.out.SourceContentFetcher;
import pl.tul.deltabrief.briefing.application.port.out.SourceContentFetcher.SourceUnavailableException;
import pl.tul.deltabrief.briefing.application.port.out.TopicSearchFeedProvider;
import pl.tul.deltabrief.briefing.domain.Briefing;
import pl.tul.deltabrief.briefing.domain.BriefingId;
import pl.tul.deltabrief.briefing.domain.BriefingType;
import pl.tul.deltabrief.briefing.domain.IngestedItem;
import pl.tul.deltabrief.topic.application.port.out.CategoryRepository;
import pl.tul.deltabrief.topic.application.port.out.TopicRepository;
import pl.tul.deltabrief.topic.application.port.out.TopicSummary;
import pl.tul.deltabrief.topic.domain.TopicId;

/**
 * Orchestrates briefing generation: resolves topic ownership, ingests each
 * source in the topic's category, calls the LLM, and persists the result.
 */
@Service
@RequiredArgsConstructor
public class BriefingService {

	private final TopicRepository topicRepository;
	private final CategoryRepository categoryRepository;
	private final FeedSourceCatalog feedSourceCatalog;
	private final TopicSearchFeedProvider topicSearchFeedProvider;
	private final SourceContentFetcher sourceContentFetcher;
	private final BriefingContentGenerator contentGenerator;
	private final BriefingRepository briefingRepository;
	private final BriefingEmailNotifier briefingEmailNotifier;

	/**
	 * @throws TopicNotFoundException if the topic doesn't exist or isn't
	 * owned by {@code userId}.
	 * @throws BriefingContentGenerator.GenerationFailedException if the AI
	 * call fails — propagated uncaught so the web layer can render a
	 * retry state; nothing is persisted in that case.
	 */
	public Briefing generateBriefing(TopicId topicId, UserId userId) {
		TopicSummary topic = topicRepository.findSummaryByIdAndUserId(topicId, userId)
				.orElseThrow(() -> new TopicNotFoundException(topicId));

		Optional<Briefing> latest = briefingRepository.findLatestByTopicId(topicId);
		BriefingType type = latest.isPresent() ? BriefingType.DELTA : BriefingType.ONBOARDING;

		List<IngestedItem> ingestedItems = ingestSources(topic);
		// A topic's categoryId is FK-constrained to an existing category row,
		// so this is always present — not a real "not found" case to handle.
		String categoryName = categoryRepository.findNameById(topic.categoryId())
				.orElseThrow(() -> new IllegalStateException(
						"Category " + topic.categoryId().value() + " referenced by a topic but not found"));

		GenerationRequest request = new GenerationRequest(topic.name(), topic.description(), categoryName, type,
				latest.map(BriefingService::toPreviousBriefing).orElse(null), ingestedItems);
		GeneratedBriefingContent content = contentGenerator.generate(request);

		Briefing briefing = Briefing.generate(topicId, type, Instant.now(), content.keyChanges(),
				content.trendContinuation(), content.noiseSpeculation(), content.significance(),
				content.uncertainties(), content.sourceImpact(), ingestedItems);
		Briefing saved = briefingRepository.save(briefing);
		// Every generation path — manual, onboarding, or scheduled — advances
		// the schedule the same way, anchored at this briefing's own
		// generatedAt (FR-009's "manual generation resets the schedule"
		// decision applies uniformly here rather than special-casing the
		// scheduler's own calls).
		topicRepository.recordSuccessfulGeneration(topicId, saved.generatedAt());
		// Same "every generation path treats this uniformly" rule applies to
		// email delivery (FR-012) — onboarding and delta briefings, manual
		// and scheduled triggers, all reach this one line.
		if (topic.emailEnabled()) {
			briefingEmailNotifier.sendBriefingEmail(userId, topic.name(), saved);
		}
		return saved;
	}

	/**
	 * Fetches every source in the topic's category, plus one topic-targeted
	 * search feed ({@link TopicSearchFeedProvider}) built from the topic's
	 * name — the category feeds are category-wide and carry no relevance
	 * signal for this specific topic, so the search feed is what actually
	 * biases ingestion toward what this topic is about. Relevance judgment
	 * for what to actually cite is left entirely to the LLM (see {@code
	 * BriefingPromptBuilder}'s {@code ANTI_HALLUCINATION_INSTRUCTION} and
	 * {@code CURATED_SOURCE_PRIORITY_GUIDANCE}) rather than pre-filtered
	 * here with a local keyword heuristic — an earlier version of this
	 * method did exactly that ({@code TopicRelevanceFilter}, since removed)
	 * and it was both unreliable (missed paraphrases like "Kyiv" for a
	 * "Ukraine" topic) and unnecessary in practice: the model already
	 * demonstrated correctly citing only the genuinely relevant handful of
	 * items even when fed the full unfiltered set.
	 *
	 * <p>Every source is fetched concurrently (each fetch is blocking I/O,
	 * a natural fit for virtual threads) rather than one after another —
	 * sequential fetching made total latency the <em>sum</em> of every
	 * source's fetch time, so adding the search feed as one more source
	 * added its full latency on top of everything else instead of a bounded
	 * amount. Results are collected by iterating the futures in their
	 * original submission order (not completion order), so {@code
	 * ingestedItems}' ordering — which is also the prompt's {@code [n]}
	 * citation numbering — stays exactly as deterministic as the old
	 * sequential version, even though the fetches themselves race.
	 *
	 * <p>Skips (doesn't fail on) any source that's unreachable — a
	 * deliberate, accepted tradeoff (plan.md): one flaky public RSS feed
	 * shouldn't block the whole feature. A source that fails here simply
	 * contributes no items; the generated briefing is based on whatever did
	 * come through. An unexpected (non-{@link SourceUnavailableException})
	 * failure still propagates and fails generation, same as the
	 * sequential version would have — only the known, already-handled
	 * per-source failure mode is swallowed.
	 */
	private List<IngestedItem> ingestSources(TopicSummary topic) {
		Instant fetchedAt = Instant.now();
		List<FeedSource> sources = new ArrayList<>(feedSourceCatalog.findByCategoryId(topic.categoryId()));
		sources.add(topicSearchFeedProvider.searchFeedFor(topic.name()));

		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<List<IngestedItem>>> futures = new ArrayList<>();
			for (FeedSource source : sources) {
				futures.add(executor.submit(() -> fetchOne(source, fetchedAt)));
			}

			List<IngestedItem> ingestedItems = new ArrayList<>();
			for (Future<List<IngestedItem>> future : futures) {
				ingestedItems.addAll(awaitResult(future));
			}
			return ingestedItems;
		}
	}

	private List<IngestedItem> fetchOne(FeedSource source, Instant fetchedAt) {
		try {
			List<IngestedItem> ingestedItems = new ArrayList<>();
			for (FetchedItem item : sourceContentFetcher.fetch(source)) {
				ingestedItems.add(new IngestedItem(source.name(), item.title(), item.link(), item.publishedAt(),
						fetchedAt));
			}
			return ingestedItems;
		} catch (SourceUnavailableException skipped) {
			return List.of();
		}
	}

	private static List<IngestedItem> awaitResult(Future<List<IngestedItem>> future) {
		try {
			return future.get();
		} catch (ExecutionException e) {
			// fetchOne only ever throws SourceUnavailableException, and
			// that's already caught inside it — anything surfacing here is
			// a genuine, unexpected failure, so it should fail generation
			// loudly rather than silently vanish as if it were just
			// another empty source.
			throw new IllegalStateException("Unexpected failure fetching a briefing source", e.getCause());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while fetching briefing sources", e);
		}
	}

	private static PreviousBriefing toPreviousBriefing(Briefing briefing) {
		return new PreviousBriefing(briefing.keyChanges(), briefing.trendContinuation(), briefing.noiseSpeculation(),
				briefing.significance(), briefing.uncertainties(), briefing.sourceImpact());
	}

	/**
	 * @return empty if the topic doesn't exist or isn't owned by {@code
	 * userId} — see {@link #generateBriefing}'s ownership check.
	 */
	public List<BriefingSummary> listSummaries(TopicId topicId, UserId userId) {
		if (topicRepository.findSummaryByIdAndUserId(topicId, userId).isEmpty()) {
			return List.of();
		}
		return briefingRepository.findSummariesByTopicId(topicId);
	}

	/**
	 * @return empty if the topic isn't owned by {@code userId}, or the
	 * briefing doesn't exist, or doesn't belong to this topic — all three
	 * cases are indistinguishable to the caller. Bundles the topic name
	 * alongside the briefing (the web layer's page title needs both) so the
	 * caller doesn't have to issue a second lookup against {@link
	 * TopicRepository} for something already fetched here for the ownership
	 * check.
	 */
	public Optional<BriefingDetail> findOne(TopicId topicId, BriefingId briefingId, UserId userId) {
		Optional<TopicSummary> topic = topicRepository.findSummaryByIdAndUserId(topicId, userId);
		if (topic.isEmpty()) {
			return Optional.empty();
		}
		return briefingRepository.findByIdAndTopicId(briefingId, topicId)
				.map(briefing -> new BriefingDetail(briefing, topic.get().name()));
	}

	/**
	 * @return empty if the topic doesn't exist or isn't owned by {@code
	 * userId}. Unlike {@link #listSummaries}, this distinguishes that case
	 * from "owned, zero briefings yet" — {@code summaries} may legitimately
	 * be empty inside a present {@code Optional}, which the web layer needs
	 * to render a friendly empty state instead of redirecting away.
	 */
	public Optional<TopicHistory> getHistory(TopicId topicId, UserId userId) {
		Optional<TopicSummary> topic = topicRepository.findSummaryByIdAndUserId(topicId, userId);
		if (topic.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new TopicHistory(topic.get().name(), briefingRepository.findSummariesByTopicId(topicId)));
	}

	public record BriefingDetail(Briefing briefing, String topicName) {
	}

	public record TopicHistory(String topicName, List<BriefingSummary> summaries) {
	}

	public static class TopicNotFoundException extends RuntimeException {
		public TopicNotFoundException(TopicId topicId) {
			super("No such topic: " + topicId.value());
		}
	}

}
