package pl.tul.deltabrief.briefing.application.port.out;

import java.util.List;
import pl.tul.deltabrief.topic.domain.CategoryId;

/**
 * Port for reading the preset sources of a topic's category. {@code
 * CategoryId} is topic's ID-record type, reused directly the same way
 * {@code UserId} already crosses into {@code topic} — an ID, not an
 * aggregate. Implemented in {@code briefing}'s own persistence adapter
 * against the shared {@code sources} table (see plan.md Phase 2).
 */
public interface FeedSourceCatalog {

	List<FeedSource> findByCategoryId(CategoryId categoryId);

}
