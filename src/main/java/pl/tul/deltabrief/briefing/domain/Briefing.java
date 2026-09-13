package pl.tul.deltabrief.briefing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.experimental.Accessors;
import pl.tul.deltabrief.topic.domain.TopicId;

/**
 * A generated briefing for a topic: either the {@link BriefingType#ONBOARDING}
 * first briefing or a {@link BriefingType#DELTA} briefing comparing against
 * the previous one. Structured per FR-010 into six narrative sections plus
 * the {@link #ingestedItems()} that back the "sources" section and every
 * inline {@code [n]} citation the sections reference.
 *
 * <p>{@link IngestedItem} is a child entity of this aggregate, not a
 * separate aggregate root — it has no lifecycle independent of the briefing
 * it was ingested for.
 */
@Getter
@Accessors(fluent = true)
public class Briefing {

	private BriefingId id;
	private final TopicId topicId;
	private final BriefingType type;
	private final Instant generatedAt;
	private final String keyChanges;
	private final String trendContinuation;
	private final String noiseSpeculation;
	private final String significance;
	private final String uncertainties;
	private final String sourceImpact;
	private final List<IngestedItem> ingestedItems;

	public Briefing(BriefingId id, TopicId topicId, BriefingType type, Instant generatedAt, String keyChanges,
			String trendContinuation, String noiseSpeculation, String significance, String uncertainties,
			String sourceImpact, List<IngestedItem> ingestedItems) {
		this.id = id;
		this.topicId = topicId;
		this.type = type;
		this.generatedAt = generatedAt;
		this.keyChanges = keyChanges;
		this.trendContinuation = trendContinuation;
		this.noiseSpeculation = noiseSpeculation;
		this.significance = significance;
		this.uncertainties = uncertainties;
		this.sourceImpact = sourceImpact;
		this.ingestedItems = List.copyOf(ingestedItems);
	}

	public static Briefing generate(TopicId topicId, BriefingType type, Instant generatedAt, String keyChanges,
			String trendContinuation, String noiseSpeculation, String significance, String uncertainties,
			String sourceImpact, List<IngestedItem> ingestedItems) {
		return new Briefing(null, topicId, type, generatedAt, keyChanges, trendContinuation, noiseSpeculation,
				significance, uncertainties, sourceImpact, ingestedItems);
	}

	public void assignId(BriefingId id) {
		this.id = Objects.requireNonNull(id);
	}

}
