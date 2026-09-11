package pl.tul.deltabrief.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * App-wide security wiring. Public paths are the placeholder landing page,
 * Render's health check, and the registration/verification pages (login's
 * own permitAll + form-login wiring lands in Phase 3); everything else
 * defaults to authenticated — this is intentional so newly added endpoints
 * are secure-by-default rather than accidentally public.
 */
@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http.authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/", "/actuator/health", "/register", "/check-email", "/verify").permitAll()
				.anyRequest().authenticated());
		return http.build();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}
}
