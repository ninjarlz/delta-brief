package pl.tul.deltabrief.shared.adapter.out.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import pl.tul.deltabrief.shared.application.EmailSender;

/**
 * Sends email via Spring Boot's auto-configured {@link JavaMailSender},
 * talking to Resend's SMTP relay (configured in {@code application.properties}).
 */
@Component
public class ResendSmtpEmailSender implements EmailSender {

	private final JavaMailSender mailSender;
	private final String fromAddress;

	public ResendSmtpEmailSender(JavaMailSender mailSender, @Value("${app.mail.from}") String fromAddress) {
		this.mailSender = mailSender;
		this.fromAddress = fromAddress;
	}

	@Override
	public void send(String to, String subject, String body) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(fromAddress);
		message.setTo(to);
		message.setSubject(subject);
		message.setText(body);
		mailSender.send(message);
	}

}
