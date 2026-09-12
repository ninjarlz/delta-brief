package pl.tul.deltabrief.auth.adapter.out.persistence;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import pl.tul.deltabrief.auth.domain.User;
import pl.tul.deltabrief.auth.domain.UserId;

/**
 * Maps between the domain {@link User} and its {@link UserJpaEntity}
 * persistence representation. {@code User}'s accessors are fluent
 * (no get/is prefix), so {@link #toEntity} maps each field via an explicit
 * expression rather than relying on MapStruct's JavaBean property matching.
 * {@link #toDomain} maps implicitly: {@code UserJpaEntity}'s getters follow
 * the JavaBean convention and match {@link User}'s constructor parameter
 * names, with {@link #toUserId} handling the {@code Long} -> {@link UserId}
 * conversion.
 */
@Mapper(componentModel = "spring")
interface UserEntityMapper {

	@Mapping(target = "id", expression = "java(user.id() != null ? user.id().value() : null)")
	@Mapping(target = "version", expression = "java(user.version())")
	@Mapping(target = "email", expression = "java(user.email())")
	@Mapping(target = "passwordHash", expression = "java(user.passwordHash())")
	@Mapping(target = "emailVerified", expression = "java(user.emailVerified())")
	@Mapping(target = "verificationToken", expression = "java(user.verificationToken())")
	@Mapping(target = "verificationTokenExpiresAt", expression = "java(user.verificationTokenExpiresAt())")
	@Mapping(target = "createdAt", expression = "java(user.createdAt())")
	UserJpaEntity toEntity(User user);

	User toDomain(UserJpaEntity entity);

	default UserId toUserId(Long id) {
		return id == null ? null : new UserId(id);
	}

}
