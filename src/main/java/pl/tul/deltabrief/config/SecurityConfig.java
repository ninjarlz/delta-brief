package pl.tul.deltabrief.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import pl.tul.deltabrief.auth.adapter.out.security.AppUserDetails;

/**
 * App-wide security wiring. Public paths are the placeholder landing page,
 * Render's health check, and the registration/verification/login pages;
 * everything else defaults to authenticated — this is intentional so newly
 * added endpoints are secure-by-default rather than accidentally public.
 */
@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http.authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/", "/actuator/health", "/register", "/check-email", "/verify", "/login",
						"/resend-verification", "/css/**")
				.permitAll()
				.anyRequest().authenticated())
			.formLogin(form -> form
				.loginPage("/login")
				.loginProcessingUrl("/login")
				.failureUrl("/login?error")
				.successHandler((request, response, authentication) -> {
					// Verification is checked here — AFTER the password has already
					// been matched by DaoAuthenticationProvider — never before, so a
					// wrong-password attempt can never reveal that an account exists
					// but is unverified.
					if (authentication.getPrincipal() instanceof AppUserDetails userDetails
							&& !userDetails.emailVerified()) {
						new SecurityContextLogoutHandler().logout(request, response, authentication);
						response.sendRedirect("/login?unverified");
						return;
					}
					response.sendRedirect("/");
				})
				.permitAll())
			.logout(logout -> logout
				.logoutUrl("/logout")
				.logoutSuccessUrl("/login?logout")
				.invalidateHttpSession(true)
				.deleteCookies("JSESSIONID")
				.permitAll());
		return http.build();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}
}
