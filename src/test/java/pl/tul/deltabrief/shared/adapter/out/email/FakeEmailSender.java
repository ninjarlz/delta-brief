package pl.tul.deltabrief.shared.adapter.out.email;

import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import pl.tul.deltabrief.shared.application.EmailSender;

/**
 * Records sent emails instead of calling Resend — {@code @Primary} so it
 * overrides {@link ResendSmtpEmailSender} in every test, meaning no test
 * needs real network access or a real API key.
 */
@Primary
@Component
public class FakeEmailSender implements EmailSender {

	private final List<SentEmail> sentEmails = new ArrayList<>();

	@Override
	public void send(String to, String subject, String body) {
		sentEmails.add(new SentEmail(to, subject, body));
	}

	public List<SentEmail> sentEmails() {
		return List.copyOf(sentEmails);
	}

	public record SentEmail(String to, String subject, String body) {
	}

}
