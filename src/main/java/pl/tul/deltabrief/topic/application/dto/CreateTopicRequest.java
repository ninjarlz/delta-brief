package pl.tul.deltabrief.topic.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

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

}
