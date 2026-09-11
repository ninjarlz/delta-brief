package pl.tul.deltabrief.placeholder;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * TEMPORARY: not a bounded context. Renders the pre-launch landing page for
 * authenticated visitors; unauthenticated visitors are sent to /login (which
 * links to /register) instead. Delete this package once the `topic` module
 * ships a real home page and repoint `/` at it.
 */
@Controller
public class PlaceholderController {

	@GetMapping("/")
	public String home(HttpServletRequest request) {
		if (request.getUserPrincipal() == null) {
			return "redirect:/login";
		}
		return "placeholder";
	}
}
