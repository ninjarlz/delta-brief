package pl.tul.deltabrief.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * App-wide security wiring. Public paths are the placeholder landing page and
 * Render's health check; everything else defaults to authenticated even
 * though no login flow exists yet — this is intentional so newly added
 * endpoints are secure-by-default rather than accidentally public.
 */
@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http.authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/", "/actuator/health").permitAll()
				.anyRequest().authenticated());
		return http.build();
	}
}
