package pl.tul.deltabrief.auth.adapter.in.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Renders {@code GET /login}. Spring Security handles the {@code POST /login}
 * submission itself — no controller method needed for that.
 */
@Controller
public class AuthPageController {

	@GetMapping("/login")
	public String showLoginForm() {
		return "login";
	}

}
