package pl.tul.deltabrief.topic.domain;

/**
 * Outcome of the most recent scheduled generation attempt for a topic.
 * {@code null} on the topic itself means no scheduled attempt has happened
 * yet — distinct from {@link #FAILURE}.
 */
public enum ScheduledRunStatus {

	SUCCESS,
	FAILURE

}
