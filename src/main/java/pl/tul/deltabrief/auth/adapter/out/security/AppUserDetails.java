package pl.tul.deltabrief.auth.adapter.out.security;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Carries {@code emailVerified} alongside the standard {@link UserDetails}
 * contract so a post-authentication check (see {@code SecurityConfig}'s
 * login success handler) can gate on verification status *after* the
 * password has already been matched — never before, which would let an
 * attacker learn an account is unverified without a valid password.
 */
public record AppUserDetails(String email, String passwordHash, boolean emailVerified) implements UserDetails {

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
