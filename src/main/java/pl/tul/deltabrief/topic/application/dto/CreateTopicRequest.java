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

}
