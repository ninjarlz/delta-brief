package pl.tul.deltabrief.briefing.application.port.out;

/**
 * A fetchable preset source, as {@code briefing} needs it for ingestion —
 * deliberately not {@code topic.domain.Source}: {@code briefing} never
 * imports {@code topic}'s domain aggregates (see plan.md's Critical
 * Implementation Details), so it owns this narrow read-model type instead.
 */
public record FeedSource(String name, String feedUrl) {
}
