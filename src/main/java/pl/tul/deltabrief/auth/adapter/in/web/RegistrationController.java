package pl.tul.deltabrief.auth.adapter.in.web;

import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import pl.tul.deltabrief.auth.application.RegistrationService;
import pl.tul.deltabrief.auth.application.RegistrationService.EmailAlreadyRegisteredException;
import pl.tul.deltabrief.auth.application.dto.RegistrationRequest;

@Controller
public class RegistrationController {

	private final RegistrationService registrationService;

	public RegistrationController(RegistrationService registrationService) {
		this.registrationService = registrationService;
	}

	@GetMapping("/register")
	public String showForm(@ModelAttribute("registrationRequest") RegistrationRequest form) {
		return "register";
	}

	@PostMapping("/register")
	public String register(@Valid @ModelAttribute("registrationRequest") RegistrationRequest form,
			BindingResult bindingResult) {
		if (!form.getPassword().equals(form.getConfirmPassword())) {
			bindingResult.rejectValue("confirmPassword", "password.mismatch", "Passwords do not match");
		}
		if (bindingResult.hasErrors()) {
			return "register";
		}
		try {
			registrationService.register(form);
		} catch (EmailAlreadyRegisteredException alreadyRegistered) {
			bindingResult.rejectValue("email", "email.taken", "This email is already registered");
			return "register";
		}
		return "redirect:/check-email";
	}

	@GetMapping("/check-email")
	public String checkEmail() {
		return "check-email";
	}

}
