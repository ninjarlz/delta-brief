package pl.tul.deltabrief.auth.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * JPA mapping for the {@code users} table, kept separate from the domain
 * {@link pl.tul.deltabrief.auth.domain.User} so the domain stays a plain
 * object with no persistence-framework dependency.
 */
@Entity
@Table(name = "users")
public class UserJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "email_verified", nullable = false)
	private boolean emailVerified;

	@Column(name = "verification_token")
	private String verificationToken;

	@Column(name = "verification_token_expires_at")
	private Instant verificationTokenExpiresAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected UserJpaEntity() {
	}

	public UserJpaEntity(Long id, String email, String passwordHash, boolean emailVerified,
			String verificationToken, Instant verificationTokenExpiresAt, Instant createdAt) {
		this.id = id;
		this.email = email;
		this.passwordHash = passwordHash;
		this.emailVerified = emailVerified;
		this.verificationToken = verificationToken;
		this.verificationTokenExpiresAt = verificationTokenExpiresAt;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public boolean isEmailVerified() {
		return emailVerified;
	}

	public String getVerificationToken() {
		return verificationToken;
	}

	public Instant getVerificationTokenExpiresAt() {
		return verificationTokenExpiresAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
