package pl.tul.deltabrief.topic.adapter.out.persistence;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import pl.tul.deltabrief.auth.domain.UserId;
import pl.tul.deltabrief.topic.domain.CategoryId;
import pl.tul.deltabrief.topic.domain.Topic;
import pl.tul.deltabrief.topic.domain.TopicId;

/**
 * Maps between the domain {@link Topic} and its {@link TopicJpaEntity}
 * persistence representation. {@code Topic}'s accessors are fluent (no
 * get prefix), so {@link #toEntity} maps each field via an explicit
 * expression rather than relying on MapStruct's JavaBean property matching.
 * {@link #toDomain} maps implicitly: {@code TopicJpaEntity}'s getters follow
 * the JavaBean convention and match {@link Topic}'s constructor parameter
 * names, with the {@code toXxx} default methods handling the {@code Long}
 * -> ID-record conversions.
 */
@Mapper(componentModel = "spring")
interface TopicEntityMapper {

	@Mapping(target = "id", expression = "java(topic.id() != null ? topic.id().value() : null)")
	@Mapping(target = "userId", expression = "java(topic.userId().value())")
	@Mapping(target = "name", expression = "java(topic.name())")
	@Mapping(target = "categoryId", expression = "java(topic.categoryId().value())")
	@Mapping(target = "description", expression = "java(topic.description())")
	@Mapping(target = "createdAt", expression = "java(topic.createdAt())")
	TopicJpaEntity toEntity(Topic topic);

	Topic toDomain(TopicJpaEntity entity);

	default TopicId toTopicId(Long id) {
		return id == null ? null : new TopicId(id);
	}

	default UserId toUserId(Long id) {
		return id == null ? null : new UserId(id);
	}

	default CategoryId toCategoryId(Long id) {
		return id == null ? null : new CategoryId(id);
	}

}
