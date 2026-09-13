package pl.tul.deltabrief.briefing.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import pl.tul.deltabrief.briefing.domain.BriefingType;

interface BriefingJpaRepository extends JpaRepository<BriefingJpaEntity, Long> {

	Optional<BriefingJpaEntity> findFirstByTopicIdOrderByGeneratedAtDesc(Long topicId);

	Optional<BriefingJpaEntity> findByIdAndTopicId(Long id, Long topicId);

	/**
	 * Spring Data interface projection — selects only {@code id}/{@code
	 * type}/{@code generatedAt}, not the full row, for the lightweight
	 * inline history list.
	 */
	List<BriefingSummaryView> findByTopicIdOrderByGeneratedAtDesc(Long topicId);

	interface BriefingSummaryView {

		Long getId();

		BriefingType getType();

		Instant getGeneratedAt();

	}

}
