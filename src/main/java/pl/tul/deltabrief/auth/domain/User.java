package pl.tul.deltabrief.auth.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * The auth aggregate: an account's email, password hash, and email
 * verification state. Password hashing itself is the application layer's
 * concern — this aggregate only ever stores an already-hashed value.
 */
public class User {

	private UserId id;
	private String email;
	private String passwordHash;
	private boolean emailVerified;
	private String verificationToken;
	private Instant verificationTokenExpiresAt;
	private final Instant createdAt;

	public User(UserId id, String email, String passwordHash, boolean emailVerified,
			String verificationToken, Instant verificationTokenExpiresAt, Instant createdAt) {
		this.id = id;
		this.email = email;
		this.passwordHash = passwordHash;
		this.emailVerified = emailVerified;
		this.verificationToken = verificationToken;
		this.verificationTokenExpiresAt = verificationTokenExpiresAt;
		this.createdAt = createdAt;
	}

	public static User register(String email, String passwordHash, Instant createdAt) {
		return new User(null, email, passwordHash, false, null, null, createdAt);
	}

	public void issueVerificationToken(String token, Instant expiresAt) {
		this.verificationToken = token;
		this.verificationTokenExpiresAt = expiresAt;
	}

	/**
	 * Verifies the email if {@code token} matches the currently issued token
	 * and hasn't expired. Clears the token fields on success so it can't be
	 * replayed.
	 *
	 * @return whether verification succeeded
	 */
	public boolean verify(String token, Instant now) {
		if (verificationToken == null || !verificationToken.equals(token)) {
			return false;
		}
		if (verificationTokenExpiresAt == null || now.isAfter(verificationTokenExpiresAt)) {
			return false;
		}
		this.emailVerified = true;
		this.verificationToken = null;
		this.verificationTokenExpiresAt = null;
		return true;
	}

	public UserId id() {
		return id;
	}

	public void assignId(UserId id) {
		this.id = Objects.requireNonNull(id);
	}

	public String email() {
		return email;
	}

	public String passwordHash() {
		return passwordHash;
	}

	public boolean emailVerified() {
		return emailVerified;
	}

	public String verificationToken() {
		return verificationToken;
	}

	public Instant verificationTokenExpiresAt() {
		return verificationTokenExpiresAt;
	}

	public Instant createdAt() {
		return createdAt;
	}

}
