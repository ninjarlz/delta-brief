package pl.tul.deltabrief.auth.adapter.out.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserJpaRepository extends JpaRepository<UserJpaEntity, Long> {

	Optional<UserJpaEntity> findByEmail(String email);

	Optional<UserJpaEntity> findByVerificationToken(String token);

	boolean existsByEmail(String email);

}
