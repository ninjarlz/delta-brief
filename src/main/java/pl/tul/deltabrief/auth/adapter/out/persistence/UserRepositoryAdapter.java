package pl.tul.deltabrief.auth.adapter.out.persistence;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pl.tul.deltabrief.auth.application.port.out.UserRepository;
import pl.tul.deltabrief.auth.domain.User;
import pl.tul.deltabrief.auth.domain.UserId;

@Component
@RequiredArgsConstructor
class UserRepositoryAdapter implements UserRepository {

	private final UserJpaRepository jpaRepository;
	private final UserEntityMapper mapper;

	@Override
	public User save(User user) {
		UserJpaEntity saved = jpaRepository.save(mapper.toEntity(user));
		user.assignId(new UserId(saved.getId()));
		user.assignVersion(saved.getVersion());
		return user;
	}

	@Override
	public Optional<User> findByEmail(String email) {
		return jpaRepository.findByEmail(email).map(mapper::toDomain);
	}

	@Override
	public Optional<User> findByVerificationToken(String token) {
		return jpaRepository.findByVerificationToken(token).map(mapper::toDomain);
	}

	@Override
	public boolean existsByEmail(String email) {
		return jpaRepository.existsByEmail(email);
	}

	@Override
	public Optional<String> findEmailById(UserId id) {
		return jpaRepository.findById(id.value()).map(UserJpaEntity::getEmail);
	}

}
