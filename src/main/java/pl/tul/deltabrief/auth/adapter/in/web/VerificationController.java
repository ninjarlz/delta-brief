package pl.tul.deltabrief.auth.adapter.in.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import pl.tul.deltabrief.auth.application.EmailVerificationService;

@Controller
public class VerificationController {

	private final EmailVerificationService emailVerificationService;

	public VerificationController(EmailVerificationService emailVerificationService) {
		this.emailVerificationService = emailVerificationService;
	}

	@GetMapping("/verify")
	public String verify(@RequestParam String token) {
		boolean verified = emailVerificationService.verify(token);
		return verified ? "redirect:/login?verified" : "redirect:/login?verification_error";
	}

}
