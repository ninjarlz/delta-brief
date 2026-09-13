package pl.tul.deltabrief.briefing.domain;

/**
 * Whether a {@link Briefing} is a topic's first briefing (no prior briefing
 * to compare against) or a delta briefing comparing against one.
 */
public enum BriefingType {
	ONBOARDING,
	DELTA
}
