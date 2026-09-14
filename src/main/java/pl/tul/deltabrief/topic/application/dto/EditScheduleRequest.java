package pl.tul.deltabrief.topic.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import pl.tul.deltabrief.topic.domain.Frequency;

/**
 * The validated edit-schedule form shape (FR-008) — only frequency,
 * preferred hour, and email opt-in are editable after creation;
 * name/category/description have no edit flow.
 */
@Getter
@Setter
public class EditScheduleRequest {

	@NotNull
	private Frequency frequency;

	@Min(0)
	@Max(23)
	private Integer preferredHour;

	/**
	 * Whether to email newly generated briefings for this topic (FR-012) —
	 * always explicitly pre-populated by the controller before the form
	 * renders, same as {@link #frequency}/{@link #preferredHour}.
	 */
	private boolean emailEnabled;

}
