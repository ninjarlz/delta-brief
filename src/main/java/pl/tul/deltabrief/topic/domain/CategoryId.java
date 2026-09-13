package pl.tul.deltabrief.topic.domain;

/**
 * Opaque identifier for a {@link Category}. Other modules reference a
 * category only by this value — never by importing {@link Category} itself.
 */
public record CategoryId(Long value) {
}
