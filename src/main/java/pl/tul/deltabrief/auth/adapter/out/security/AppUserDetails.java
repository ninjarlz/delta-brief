package pl.tul.deltabrief.auth.adapter.out.security;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import pl.tul.deltabrief.auth.domain.UserId;

/**
 * Carries {@code userId} and {@code emailVerified} alongside the standard
 * {@link UserDetails} contract. {@code userId} lets any controller resolve
 * "who is making this request" via {@code (AppUserDetails)
 * authentication.getPrincipal()} without an extra repository lookup —
 * {@code JpaUserDetailsService} already has the loaded {@code User} (and
 * thus its ID) in hand when constructing this. {@code emailVerified}
 * supports a post-authentication check (see {@code SecurityConfig}'s login
 * success handler) that gates on verification status *after* the password
 * has already been matched — never before, which would let an attacker
 * learn an account is unverified without a valid password.
 */
public record AppUserDetails(UserId userId, String email, String passwordHash, boolean emailVerified)
		implements UserDetails {

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return AuthorityUtils.createAuthorityList("ROLE_USER");
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	@Override
	public String getUsername() {
		return email;
	}

}
