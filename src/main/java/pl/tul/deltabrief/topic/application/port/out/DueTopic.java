package pl.tul.deltabrief.topic.application.port.out;

import pl.tul.deltabrief.auth.domain.UserId;
import pl.tul.deltabrief.topic.domain.TopicId;

/**
 * A topic due for scheduled generation (FR-009) — just enough for the
 * scheduler (which lives in the {@code briefing} module, since that's what
 * already owns generation) to call {@code BriefingService.generateBriefing}
 * the same way an HTTP request would. The schedule math itself
 * (frequency/preferredHour) stays inside the {@code topic} module — see
 * {@link TopicRepository#recordSuccessfulGeneration}.
 */
public record DueTopic(TopicId id, UserId userId) {
}
