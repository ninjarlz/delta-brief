package pl.tul.deltabrief.briefing.application;

import java.util.List;
import java.util.Optional;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import pl.tul.deltabrief.auth.application.port.out.UserRepository;
import pl.tul.deltabrief.auth.domain.UserId;
import pl.tul.deltabrief.briefing.domain.Briefing;
import pl.tul.deltabrief.briefing.domain.BriefingType;
import pl.tul.deltabrief.briefing.domain.CitationRenderer;
import pl.tul.deltabrief.briefing.domain.CitationRenderer.RenderedBriefing;
import pl.tul.deltabrief.briefing.domain.IngestedItem;
import pl.tul.deltabrief.shared.application.EmailDeliveryException;
import pl.tul.deltabrief.shared.application.EmailSender;

/**
 * Emails a just-generated briefing to its topic's owner, for topics opted in
 * to email delivery (FR-012, S-06). Deliberately a separate bean from
 * {@link BriefingService} rather than a method on it — {@code @Async} only
 * intercepts calls that go through Spring's proxy, and
 * {@code generateBriefing} calling a method on itself would bypass that
 * proxy entirely (self-invocation), silently making the "async" send run
 * synchronously instead. Mirrors {@code RegistrationService.resendVerification}'s
 * existing {@code @Async} pattern for exactly this reason.
 */
@Component
@Log4j2
public class BriefingEmailNotifier {

	private final UserRepository userRepository;
	private final EmailSender emailSender;
	private final String baseUrl;

	public BriefingEmailNotifier(UserRepository userRepository, EmailSender emailSender,
			@Value("${app.base-url:http://localhost:8080}") String baseUrl) {
		this.userRepository = userRepository;
		this.emailSender = emailSender;
		this.baseUrl = baseUrl;
	}

	/**
	 * Non-fatal on any failure — a topic's owner not being resolvable
	 * (shouldn't happen in practice) or the send itself failing are both
	 * logged and swallowed, identical to
	 * {@code RegistrationService.sendVerificationEmail}'s existing
	 * precedent: the briefing itself is already saved successfully by the
	 * time this runs, so email delivery failure must never affect that.
	 *
	 * @param frequencyAdjective the topic's frequency, pre-translated to a
	 * lowercase email-wording adjective (e.g. "daily") by {@code
	 * TopicRepositoryAdapter} — unused for {@link BriefingType#ONBOARDING},
	 * which has no cadence to describe.
	 */
	@Async("emailTaskExecutor")
	public void sendBriefingEmail(UserId userId, String topicName, String frequencyAdjective, Briefing briefing) {
		Optional<String> recipient = userRepository.findEmailById(userId);
		if (recipient.isEmpty()) {
			log.warn(">>> Cannot email briefing {} for topic '{}': no email found for user {}", briefing.id().value(),
					topicName, userId.value());
			return;
		}
		String headline = headline(briefing.type(), frequencyAdjective, topicName);
		try {
			emailSender.send(recipient.get(), headline, buildBody(headline, briefing));
		} catch (EmailDeliveryException emailDeliveryFailed) {
			log.warn(">>> Failed to email briefing {} to {}: {}", briefing.id().value(), recipient.get(),
					emailDeliveryFailed.getMessage());
		}
	}

	private String buildBody(String headline, Briefing briefing) {
		RenderedBriefing rendered = CitationRenderer.render(briefing);
		StringBuilder body = new StringBuilder();
		body.append(headline).append("\n\n");
		appendSection(body, "Key changes", rendered.keyChanges());
		appendSection(body, "Trend continuation", rendered.trendContinuation());
		appendSection(body, "Noise & speculation", rendered.noiseSpeculation());
		appendSection(body, "Significance", rendered.significance());
		appendSection(body, "Uncertainties", rendered.uncertainties());
		appendSection(body, "Source impact on scenarios", rendered.sourceImpact());
		appendSources(body, rendered.citedSources());
		body.append("View this briefing online: ").append(baseUrl).append("/topics/")
				.append(briefing.topicId().value()).append("/briefings/").append(briefing.id().value());
		return body.toString();
	}

	private static void appendSection(StringBuilder body, String heading, String text) {
		body.append(heading).append('\n').append(text).append("\n\n");
	}

	private static void appendSources(StringBuilder body, List<IngestedItem> sources) {
		body.append("Sources\n");
		if (sources.isEmpty()) {
			body.append("No sources were explicitly cited in this briefing.\n\n");
			return;
		}
		for (int i = 0; i < sources.size(); i++) {
			IngestedItem item = sources.get(i);
			body.append(i + 1).append(". ").append(item.sourceName()).append(" — ").append(item.title())
					.append(" (").append(item.link()).append(")\n");
		}
		body.append('\n');
	}

	/**
	 * Subject line and body opening — deliberately the same string for both,
	 * per the existing convention of leading the body with the subject's own
	 * wording. Onboarding is a one-off, not a cadence, so it gets its own
	 * phrasing rather than a frequency adjective that wouldn't make sense.
	 */
	private static String headline(BriefingType type, String frequencyAdjective, String topicName) {
		return switch (type) {
			case ONBOARDING -> "Your first briefing for %s is ready".formatted(topicName);
			case DELTA -> "It's your %s delta briefing for %s".formatted(frequencyAdjective, topicName);
		};
	}

}
