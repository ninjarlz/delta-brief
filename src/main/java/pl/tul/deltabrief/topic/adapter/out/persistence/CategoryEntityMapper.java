package pl.tul.deltabrief.topic.adapter.out.persistence;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import pl.tul.deltabrief.topic.domain.Category;
import pl.tul.deltabrief.topic.domain.CategoryId;

/**
 * Maps between the domain {@link Category} and its {@link CategoryJpaEntity}
 * persistence representation. {@code Category}'s accessors are fluent (no
 * get prefix), so {@link #toEntity} maps each field via an explicit
 * expression rather than relying on MapStruct's JavaBean property matching.
 */
@Mapper(componentModel = "spring")
interface CategoryEntityMapper {

	@Mapping(target = "id", expression = "java(category.id() != null ? category.id().value() : null)")
	@Mapping(target = "name", expression = "java(category.name())")
	CategoryJpaEntity toEntity(Category category);

	Category toDomain(CategoryJpaEntity entity);

	default CategoryId toCategoryId(Long id) {
		return id == null ? null : new CategoryId(id);
	}

}
