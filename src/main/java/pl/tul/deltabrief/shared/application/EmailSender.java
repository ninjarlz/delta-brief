package pl.tul.deltabrief.shared.application;

/**
 * Generic, auth-independent port for sending a plain-text email. Deliberately
 * lives in {@code shared} rather than {@code auth} — S-06 (email-briefing-delivery)
 * reuses this same abstraction and Resend account.
 */
public interface EmailSender {

	void send(String to, String subject, String body);

}
