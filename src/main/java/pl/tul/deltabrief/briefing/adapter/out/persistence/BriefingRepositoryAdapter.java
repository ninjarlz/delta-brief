package pl.tul.deltabrief.briefing.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pl.tul.deltabrief.briefing.adapter.out.persistence.BriefingJpaRepository.BriefingSummaryView;
import pl.tul.deltabrief.briefing.application.port.out.BriefingRepository;
import pl.tul.deltabrief.briefing.application.port.out.BriefingSummary;
import pl.tul.deltabrief.briefing.domain.Briefing;
import pl.tul.deltabrief.briefing.domain.BriefingId;
import pl.tul.deltabrief.briefing.domain.IngestedItem;
import pl.tul.deltabrief.topic.domain.TopicId;

/**
 * Persists a {@link Briefing} and its {@link IngestedItem} child rows across
 * two flat tables ({@code briefings}, {@code ingested_items}) — no JPA
 * relationship annotation is used anywhere in this codebase, so this adapter
 * saves the parent first to obtain its generated id, then saves each child
 * row with that id, and re-assembles both on read.
 */
@Component
@RequiredArgsConstructor
class BriefingRepositoryAdapter implements BriefingRepository {

	private final BriefingJpaRepository briefingJpaRepository;
	private final IngestedItemJpaRepository ingestedItemJpaRepository;
	private final BriefingEntityMapper mapper;

	@Override
	@Transactional
	public Briefing save(Briefing briefing) {
		BriefingJpaEntity savedBriefing = briefingJpaRepository.save(mapper.toEntity(briefing));
		briefing.assignId(new BriefingId(savedBriefing.getId()));
		List<IngestedItemJpaEntity> items = briefing.ingestedItems().stream()
				.map(item -> mapper.toEntity(item, savedBriefing.getId()))
				.toList();
		ingestedItemJpaRepository.saveAll(items);
		return briefing;
	}

	@Override
	public Optional<Briefing> findLatestByTopicId(TopicId topicId) {
		return briefingJpaRepository.findFirstByTopicIdOrderByGeneratedAtDesc(topicId.value())
				.map(this::toDomainWithItems);
	}

	@Override
	public Optional<Briefing> findByIdAndTopicId(BriefingId id, TopicId topicId) {
		return briefingJpaRepository.findByIdAndTopicId(id.value(), topicId.value())
				.map(this::toDomainWithItems);
	}

	@Override
	public List<BriefingSummary> findSummariesByTopicId(TopicId topicId) {
		return briefingJpaRepository.findByTopicIdOrderByGeneratedAtDesc(topicId.value()).stream()
				.map(this::toSummary)
				.toList();
	}

	private Briefing toDomainWithItems(BriefingJpaEntity entity) {
		List<IngestedItem> items = ingestedItemJpaRepository.findByBriefingId(entity.getId()).stream()
				.map(mapper::toDomain)
				.toList();
		return mapper.toDomain(entity, items);
	}

	private BriefingSummary toSummary(BriefingSummaryView view) {
		return new BriefingSummary(new BriefingId(view.getId()), view.getType(), view.getGeneratedAt());
	}

}
