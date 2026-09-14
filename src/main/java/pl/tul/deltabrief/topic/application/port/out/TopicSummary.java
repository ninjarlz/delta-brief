package pl.tul.deltabrief.topic.application.port.out;

import pl.tul.deltabrief.topic.domain.CategoryId;

/**
 * Lightweight view of a {@link pl.tul.deltabrief.topic.domain.Topic} for
 * cross-module consumers (e.g. {@code briefing}) that need the name,
 * category, optional description, and email-delivery preference without
 * importing the {@code Topic} aggregate itself.
 *
 * @param description the user's optional observation-goal note (FR-004) —
 * {@code null} if not provided.
 * @param emailEnabled whether the topic's owner opted in to receive
 * generated briefings by email (FR-012, S-06).
 */
public record TopicSummary(String name, CategoryId categoryId, String description, boolean emailEnabled) {
}
