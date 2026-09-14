package pl.tul.deltabrief.topic.application.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import pl.tul.deltabrief.topic.domain.Frequency;

/**
 * The validated edit-schedule form shape (FR-008) — only frequency,
 * preferred time, and email opt-in are editable after creation;
 * name/category/description have no edit flow.
 */
@Getter
@Setter
public class EditScheduleRequest {

	@NotNull
	private Frequency frequency;

	/**
	 * Optional UTC time-of-day to nudge scheduled generation toward —
	 * {@code null} means no preference. Bound from a hidden field that
	 * {@code app.js} populates with the UTC equivalent of whatever local time
	 * the user picked in the visible {@code <input type="time">}.
	 */
	@DateTimeFormat(pattern = "HH:mm")
	private LocalTime preferredTime;

	/**
	 * Whether to email newly generated briefings for this topic (FR-012) —
	 * always explicitly pre-populated by the controller before the form
	 * renders, same as {@link #frequency}/{@link #preferredTime}.
	 */
	private boolean emailEnabled;

}
