package pl.tul.deltabrief.auth.adapter.out.persistence;

import java.util.Optional;
import org.springframework.stereotype.Component;
import pl.tul.deltabrief.auth.application.port.out.UserRepository;
import pl.tul.deltabrief.auth.domain.User;
import pl.tul.deltabrief.auth.domain.UserId;

@Component
class UserRepositoryAdapter implements UserRepository {

	private final UserJpaRepository jpaRepository;

	UserRepositoryAdapter(UserJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	@Override
	public User save(User user) {
		UserJpaEntity entity = new UserJpaEntity(
				user.id() != null ? user.id().value() : null,
				user.version(),
				user.email(),
				user.passwordHash(),
				user.emailVerified(),
				user.verificationToken(),
				user.verificationTokenExpiresAt(),
				user.createdAt());
		UserJpaEntity saved = jpaRepository.save(entity);
		user.assignId(new UserId(saved.getId()));
		user.assignVersion(saved.getVersion());
		return user;
	}

	@Override
	public Optional<User> findByEmail(String email) {
		return jpaRepository.findByEmail(email).map(UserRepositoryAdapter::toDomain);
	}

	@Override
	public Optional<User> findByVerificationToken(String token) {
		return jpaRepository.findByVerificationToken(token).map(UserRepositoryAdapter::toDomain);
	}

	@Override
	public boolean existsByEmail(String email) {
		return jpaRepository.existsByEmail(email);
	}

	private static User toDomain(UserJpaEntity entity) {
		return new User(
				new UserId(entity.getId()),
				entity.getVersion(),
				entity.getEmail(),
				entity.getPasswordHash(),
				entity.isEmailVerified(),
				entity.getVerificationToken(),
				entity.getVerificationTokenExpiresAt(),
				entity.getCreatedAt());
	}

}
