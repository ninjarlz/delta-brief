package pl.tul.deltabrief.placeholder;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * TEMPORARY: not a bounded context. Renders the pre-launch landing page.
 * Delete this package once the `topic` module ships a real home page and
 * repoint `/` at it.
 */
@Controller
public class PlaceholderController {

	@GetMapping("/")
	public String home() {
		return "placeholder";
	}
}
