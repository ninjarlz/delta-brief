package pl.tul.deltabrief.briefing.adapter.out.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import pl.tul.deltabrief.briefing.application.port.out.BriefingContentGenerator;

/**
 * Calls OpenAI (via Spring AI's {@link ChatClient}) to produce the briefing's
 * six narrative sections as structured output. {@code ChatClient} is built
 * once from the autoconfigured {@code ChatClient.Builder} — hand-written
 * constructor rather than {@code @RequiredArgsConstructor} since the field
 * is derived from the injected builder, not the builder itself.
 */
@Component
class OpenAiBriefingContentGenerator implements BriefingContentGenerator {

	private final ChatClient chatClient;
	private final BriefingPromptBuilder promptBuilder;

	OpenAiBriefingContentGenerator(ChatClient.Builder chatClientBuilder, BriefingPromptBuilder promptBuilder) {
		this.chatClient = chatClientBuilder.build();
		this.promptBuilder = promptBuilder;
	}

	@Override
	public GeneratedBriefingContent generate(GenerationRequest request) {
		try {
			String prompt = promptBuilder.build(request);
			return chatClient.prompt().user(prompt).call().entity(GeneratedBriefingContent.class);
		} catch (RuntimeException e) {
			throw new GenerationFailedException(e);
		}
	}

}
