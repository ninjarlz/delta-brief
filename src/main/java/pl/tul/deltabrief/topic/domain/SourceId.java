package pl.tul.deltabrief.topic.domain;

/**
 * Opaque identifier for a {@link Source}. Other modules reference a source
 * only by this value — never by importing {@link Source} itself.
 */
public record SourceId(Long value) {
}
