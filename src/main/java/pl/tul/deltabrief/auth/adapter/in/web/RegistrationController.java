package pl.tul.deltabrief.auth.adapter.in.web;

import com.giffing.bucket4j.spring.boot.starter.context.RateLimiting;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import pl.tul.deltabrief.auth.application.RegistrationService;
import pl.tul.deltabrief.auth.application.RegistrationService.EmailAlreadyRegisteredException;
import pl.tul.deltabrief.auth.application.dto.RegistrationRequest;

@Validated
@Controller
@RequiredArgsConstructor
public class RegistrationController {

	private final RegistrationService registrationService;

	@GetMapping("/register")
	public String showForm(@ModelAttribute("registrationRequest") RegistrationRequest form) {
		return "register";
	}

	@PostMapping("/register")
	// Rate-limited before validation or any service work runs (AOP wraps the
	// whole method), so even a flood of malformed submissions is throttled.
	// Uses the same "registration" bucket4j.methods[] config as
	// resendVerification() below (application.properties), but
	// @RateLimiting always scopes the cache key by the declaring method name
	// internally (confirmed empirically, not documented) — so this endpoint
	// gets its own independent 5/15min bucket, not a budget shared with
	// resendVerification(). See shared's RateLimitExceededAdvice for the
	// 429 response.
	@RateLimiting(name = "registration", cacheKey = "#form.email + ':' + #request.remoteAddr")
	public String register(@Valid @ModelAttribute("registrationRequest") RegistrationRequest form,
			BindingResult bindingResult, HttpServletRequest request) {
		if (!Objects.equals(form.getPassword(), form.getConfirmPassword())) {
			bindingResult.rejectValue("confirmPassword", "password.mismatch", "Passwords do not match");
		}
		if (bindingResult.hasErrors()) {
			return clearPasswordsAndReturnToForm(form);
		}
		try {
			registrationService.register(form);
		} catch (EmailAlreadyRegisteredException alreadyRegistered) {
			bindingResult.rejectValue("email", "email.taken", "This email is already registered");
			return clearPasswordsAndReturnToForm(form);
		}
		return "redirect:/check-email";
	}

	private String clearPasswordsAndReturnToForm(RegistrationRequest form) {
		form.setPassword(null);
		form.setConfirmPassword(null);
		return "register";
	}

	@GetMapping("/check-email")
	public String checkEmail() {
		return "check-email";
	}

	@GetMapping("/resend-verification")
	public String showResendVerificationForm() {
		return "resend-verification";
	}

	@PostMapping("/resend-verification")
	@RateLimiting(name = "registration", cacheKey = "#email + ':' + #request.remoteAddr")
	public String resendVerification(@RequestParam("email") @Email @Size(max = 255) String email,
			HttpServletRequest request) {
		registrationService.resendVerification(email);
		return "redirect:/check-email";
	}

	/**
	 * A malformed email can never match a registered account anyway, so this
	 * lands on the same generic outcome as any other non-matching input —
	 * never a stack trace, and no information the uniform redirect wouldn't
	 * already reveal.
	 */
	@ExceptionHandler(ConstraintViolationException.class)
	public String handleInvalidResendEmail() {
		return "redirect:/check-email";
	}

}
