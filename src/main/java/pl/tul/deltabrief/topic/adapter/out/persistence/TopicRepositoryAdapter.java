package pl.tul.deltabrief.topic.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pl.tul.deltabrief.auth.domain.UserId;
import pl.tul.deltabrief.topic.application.port.out.DueTopic;
import pl.tul.deltabrief.topic.application.port.out.TopicRepository;
import pl.tul.deltabrief.topic.application.port.out.TopicSummary;
import pl.tul.deltabrief.topic.domain.CategoryId;
import pl.tul.deltabrief.topic.domain.ScheduleCalculator;
import pl.tul.deltabrief.topic.domain.Topic;
import pl.tul.deltabrief.topic.domain.TopicId;

@Component
@RequiredArgsConstructor
class TopicRepositoryAdapter implements TopicRepository {

	private final TopicJpaRepository jpaRepository;
	private final TopicEntityMapper mapper;

	@Override
	public Topic save(Topic topic) {
		TopicJpaEntity saved = jpaRepository.save(mapper.toEntity(topic));
		topic.assignId(new TopicId(saved.getId()));
		return topic;
	}

	@Override
	public List<Topic> findAllByUserId(UserId userId) {
		return jpaRepository.findAllByUserId(userId.value()).stream().map(mapper::toDomain).toList();
	}

	@Override
	public boolean existsByUserIdAndNameIgnoreCase(UserId userId, String name) {
		return jpaRepository.existsByUserIdAndNameIgnoreCase(userId.value(), name);
	}

	@Override
	public long countByUserId(UserId userId) {
		return jpaRepository.countByUserId(userId.value());
	}

	@Override
	public Optional<Topic> findByIdAndUserId(TopicId id, UserId userId) {
		return jpaRepository.findEntityByIdAndUserId(id.value(), userId.value()).map(mapper::toDomain);
	}

	@Override
	public Optional<TopicSummary> findSummaryByIdAndUserId(TopicId id, UserId userId) {
		return jpaRepository.findByIdAndUserId(id.value(), userId.value())
				.map(view -> new TopicSummary(view.getName(), new CategoryId(view.getCategoryId()),
						view.getDescription(), view.getEmailEnabled(), view.getFrequency().emailAdjective()));
	}

	@Override
	public boolean deleteByIdAndUserId(TopicId id, UserId userId) {
		return jpaRepository.deleteByIdAndUserId(id.value(), userId.value()) > 0;
	}

	@Override
	public List<DueTopic> findDueForScheduledGeneration(Instant now) {
		return jpaRepository.findDueForScheduledGeneration(now).stream()
				.map(entity -> new DueTopic(new TopicId(entity.getId()), new UserId(entity.getUserId())))
				.toList();
	}

	@Override
	public void recordSuccessfulGeneration(TopicId id, Instant generatedAt) {
		jpaRepository.findById(id.value()).ifPresent(entity -> {
			Topic topic = mapper.toDomain(entity);
			Instant nextDueAt = ScheduleCalculator.nextDueAt(generatedAt, topic.frequency(), topic.preferredTime());
			topic.recordScheduledSuccess(generatedAt, nextDueAt);
			jpaRepository.save(mapper.toEntity(topic));
		});
	}

	@Override
	public void recordFailedScheduledGeneration(TopicId id, Instant attemptedAt) {
		jpaRepository.findById(id.value()).ifPresent(entity -> {
			Topic topic = mapper.toDomain(entity);
			topic.recordScheduledFailure(attemptedAt);
			jpaRepository.save(mapper.toEntity(topic));
		});
	}

}
