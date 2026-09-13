package pl.tul.deltabrief.briefing.adapter.out.persistence;

import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import pl.tul.deltabrief.briefing.domain.Briefing;
import pl.tul.deltabrief.briefing.domain.BriefingId;
import pl.tul.deltabrief.briefing.domain.IngestedItem;
import pl.tul.deltabrief.topic.domain.TopicId;

/**
 * Maps between the domain {@link Briefing}/{@link IngestedItem} and their
 * JPA entity representations. Both domain types use fluent accessors (no
 * {@code get} prefix), so every {@code toEntity}/mixed-source mapping is
 * explicit rather than relying on MapStruct's JavaBean property matching.
 * {@code ingestedItems} is loaded/persisted via a separate query (see
 * {@code BriefingRepositoryAdapter}), so {@link #toDomain} takes the
 * already-loaded list as a second source parameter.
 */
@Mapper(componentModel = "spring")
interface BriefingEntityMapper {

	@Mapping(target = "id", expression = "java(briefing.id() != null ? briefing.id().value() : null)")
	@Mapping(target = "topicId", expression = "java(briefing.topicId().value())")
	@Mapping(target = "type", expression = "java(briefing.type())")
	@Mapping(target = "generatedAt", expression = "java(briefing.generatedAt())")
	@Mapping(target = "keyChanges", expression = "java(briefing.keyChanges())")
	@Mapping(target = "trendContinuation", expression = "java(briefing.trendContinuation())")
	@Mapping(target = "noiseSpeculation", expression = "java(briefing.noiseSpeculation())")
	@Mapping(target = "significance", expression = "java(briefing.significance())")
	@Mapping(target = "uncertainties", expression = "java(briefing.uncertainties())")
	@Mapping(target = "sourceImpact", expression = "java(briefing.sourceImpact())")
	BriefingJpaEntity toEntity(Briefing briefing);

	Briefing toDomain(BriefingJpaEntity entity, List<IngestedItem> ingestedItems);

	@Mapping(target = "id", ignore = true)
	@Mapping(target = "briefingId", source = "briefingId")
	@Mapping(target = "sourceName", expression = "java(item.sourceName())")
	@Mapping(target = "title", expression = "java(item.title())")
	@Mapping(target = "link", expression = "java(item.link())")
	@Mapping(target = "publishedAt", expression = "java(item.publishedAt())")
	@Mapping(target = "fetchedAt", expression = "java(item.fetchedAt())")
	IngestedItemJpaEntity toEntity(IngestedItem item, Long briefingId);

	IngestedItem toDomain(IngestedItemJpaEntity entity);

	default BriefingId toBriefingId(Long id) {
		return id == null ? null : new BriefingId(id);
	}

	default TopicId toTopicId(Long id) {
		return id == null ? null : new TopicId(id);
	}

}
