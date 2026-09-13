package pl.tul.deltabrief.topic.domain;

/**
 * Opaque identifier for a {@link Topic}. Other modules reference a topic
 * only by this value — never by importing {@link Topic} itself.
 */
public record TopicId(Long value) {
}
