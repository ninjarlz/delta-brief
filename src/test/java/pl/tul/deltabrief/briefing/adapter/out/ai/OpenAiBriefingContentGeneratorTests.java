package pl.tul.deltabrief.briefing.adapter.out.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.wiremock.spring.EnableWireMock;
import pl.tul.deltabrief.briefing.application.port.out.BriefingContentGenerator;
import pl.tul.deltabrief.briefing.application.port.out.BriefingContentGenerator.GeneratedBriefingContent;
import pl.tul.deltabrief.briefing.application.port.out.BriefingContentGenerator.GenerationFailedException;
import pl.tul.deltabrief.briefing.application.port.out.BriefingContentGenerator.GenerationRequest;
import pl.tul.deltabrief.briefing.domain.BriefingType;
import pl.tul.deltabrief.briefing.domain.IngestedItem;

/**
 * Stubs OpenAI's chat-completions endpoint via WireMock — no real call to
 * OpenAI happens in this test (see plan.md's WireMock stubbing points).
 * {@code spring.ai.openai.base-url} is overridden to WireMock's own base
 * URL, the standard Spring AI OpenAI override point for exactly this
 * purpose. {@code bucket4j.enabled=false} + {@code spring.cache.type=none}
 * avoid a JCache/Caffeine CacheManager collision between this test's Spring
 * context and other already-cached contexts in the same test JVM (Caffeine's
 * JCache provider is a JVM-singleton keyed by URI, not context-scoped) —
 * caching/rate-limiting is irrelevant to what this test exercises.
 */
@SpringBootTest(properties = {"app.async.email.enabled=false", "bucket4j.enabled=false", "spring.cache.type=none",
		"spring.ai.openai.base-url=${wiremock.server.baseUrl}"})
@EnableWireMock
class OpenAiBriefingContentGeneratorTests {

	private static final String CHAT_COMPLETION_RESPONSE = """
			{
			  "id": "chatcmpl-test",
			  "object": "chat.completion",
			  "created": 1700000000,
			  "model": "gpt-4o-mini",
			  "choices": [
			    {
			      "index": 0,
			      "message": {
			        "role": "assistant",
			        "content": "{\\"keyChanges\\":\\"Genuine change [1]\\",\\"trendContinuation\\":\\"Trend note\\",\\"noiseSpeculation\\":\\"No noise detected\\",\\"significance\\":\\"Significant\\",\\"uncertainties\\":\\"Some uncertainty\\",\\"sourceImpact\\":\\"Scenario impact\\"}"
			      },
			      "finish_reason": "stop"
			    }
			  ],
			  "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20}
			}
			""";

	@Autowired
	private BriefingContentGenerator generator;

	private static GenerationRequest onboardingRequest() {
		Instant now = Instant.now();
		List<IngestedItem> items = List.of(new IngestedItem("BBC News", "Headline", "https://example.com/1", now,
				now));
		return new GenerationRequest("War in Ukraine", "World News", BriefingType.ONBOARDING, null, items);
	}

	@Test
	void generatesStructuredContentFromTheChatResponse() {
		stubFor(post(urlEqualTo("/chat/completions")).willReturn(
				aResponse().withHeader("Content-Type", "application/json").withBody(CHAT_COMPLETION_RESPONSE)));

		GeneratedBriefingContent content = generator.generate(onboardingRequest());

		assertThat(content.keyChanges()).isEqualTo("Genuine change [1]");
		assertThat(content.trendContinuation()).isEqualTo("Trend note");
		assertThat(content.noiseSpeculation()).isEqualTo("No noise detected");
		assertThat(content.significance()).isEqualTo("Significant");
		assertThat(content.uncertainties()).isEqualTo("Some uncertainty");
		assertThat(content.sourceImpact()).isEqualTo("Scenario impact");
	}

	@Test
	void wrapsAFailedCallAsGenerationFailedException() {
		stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withStatus(500)));

		assertThatExceptionOfType(GenerationFailedException.class)
				.isThrownBy(() -> generator.generate(onboardingRequest()));
	}

}
