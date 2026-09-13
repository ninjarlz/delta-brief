package pl.tul.deltabrief.briefing.application.port.out;

import java.time.Instant;
import pl.tul.deltabrief.briefing.domain.BriefingId;
import pl.tul.deltabrief.briefing.domain.BriefingType;

/**
 * Lightweight view of a {@link pl.tul.deltabrief.briefing.domain.Briefing}
 * for the inline history list — does not hydrate {@code ingestedItems}.
 */
public record BriefingSummary(BriefingId id, BriefingType type, Instant generatedAt) {
}
