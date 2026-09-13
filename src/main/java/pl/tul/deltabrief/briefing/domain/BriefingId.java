package pl.tul.deltabrief.briefing.domain;

/**
 * Opaque identifier for a {@link Briefing}. Other modules reference a
 * briefing only by this value — never by importing {@link Briefing} itself.
 */
public record BriefingId(Long value) {
}
