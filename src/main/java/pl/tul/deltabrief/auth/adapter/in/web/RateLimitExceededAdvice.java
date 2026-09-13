package pl.tul.deltabrief.auth.adapter.in.web;

import com.giffing.bucket4j.spring.boot.starter.context.RateLimitException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Global handler for {@code @RateLimiting}-annotated methods (see
 * {@link RegistrationController}) — the bucket4j-spring-boot-starter aspect
 * throws {@link RateLimitException} when a limit is exceeded instead of
 * returning a response itself, so this maps it to the existing styled
 * too-many-requests page. Not scoped to one controller: any future
 * {@code @RateLimiting}-annotated endpoint gets the same 429 response for
 * free.
 */
@ControllerAdvice
class RateLimitExceededAdvice {

	@ExceptionHandler(RateLimitException.class)
	String handleRateLimitExceeded(HttpServletResponse response) {
		response.setStatus(429); // HTTP 429 Too Many Requests — not a constant in this Jakarta Servlet version
		return "too-many-requests";
	}

}
