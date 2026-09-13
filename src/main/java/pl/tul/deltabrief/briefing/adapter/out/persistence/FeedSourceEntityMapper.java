package pl.tul.deltabrief.briefing.adapter.out.persistence;

import org.mapstruct.Mapper;
import pl.tul.deltabrief.briefing.application.port.out.FeedSource;

/**
 * Maps {@link FeedSourceJpaEntity} to the {@link FeedSource} record.
 * Unlike this codebase's other mappers, no explicit {@code @Mapping}
 * expressions are needed: {@code FeedSource} is a plain record (not a
 * fluent-accessor domain aggregate), so MapStruct's record support matches
 * its {@code name()}/{@code feedUrl()} accessors to the entity's JavaBean
 * getters by property name directly.
 */
@Mapper(componentModel = "spring")
interface FeedSourceEntityMapper {

	FeedSource toDomain(FeedSourceJpaEntity entity);

}
