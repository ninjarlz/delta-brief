package pl.tul.deltabrief.shared.application;

/**
 * Thrown by {@link EmailSender} implementations to signal a delivery
 * failure, without leaking the specific transport (SMTP, an HTTP API,
 * etc.) to callers. Keeps the {@code application} layer depending only on
 * this port's own contract, not on an adapter-specific exception type.
 */
public class EmailDeliveryException extends RuntimeException {

	public EmailDeliveryException(String message, Throwable cause) {
		super(message, cause);
	}

}
