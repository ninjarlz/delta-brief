package pl.tul.deltabrief.topic.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import pl.tul.deltabrief.topic.domain.Frequency;

/**
 * The validated create-topic form shape.
 */
@Getter
@Setter
public class CreateTopicRequest {

	@NotBlank
	@Size(max = 255)
	private String name;

	@NotNull
	private Long categoryId;

	/**
	 * Optional observation-goal note (FR-004) — fed into future briefing
	 * generation prompts for this topic. No {@code @NotBlank}: unlike
	 * {@code name}, this field is genuinely optional.
	 */
	@Size(max = 1000)
	private String description;

	/**
	 * How often DeltaBrief should generate this topic's briefing
	 * automatically (FR-008) — defaults to {@code DAILY} so the form starts
	 * with the product default pre-selected.
	 */
	@NotNull
	private Frequency frequency = Frequency.DAILY;

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
	 * defaults to {@code true} (checked, recommended) on a fresh form render.
	 */
	private boolean emailEnabled = true;

}
