package pl.tul.deltabrief.topic.adapter.out.persistence;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pl.tul.deltabrief.auth.domain.UserId;
import pl.tul.deltabrief.topic.application.port.out.TopicRepository;
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
	public boolean deleteByIdAndUserId(TopicId id, UserId userId) {
		return jpaRepository.deleteByIdAndUserId(id.value(), userId.value()) > 0;
	}

}
