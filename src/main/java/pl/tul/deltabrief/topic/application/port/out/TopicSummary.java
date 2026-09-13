package pl.tul.deltabrief.topic.application.port.out;

import pl.tul.deltabrief.topic.domain.CategoryId;

/**
 * Lightweight view of a {@link pl.tul.deltabrief.topic.domain.Topic} for
 * cross-module consumers (e.g. {@code briefing}) that need the name,
 * category, and optional description without importing the {@code Topic}
 * aggregate itself.
 *
 * @param description the user's optional observation-goal note (FR-004) —
 * {@code null} if not provided.
 */
public record TopicSummary(String name, CategoryId categoryId, String description) {
}
