package pl.tul.deltabrief.briefing.adapter.out.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Internal to {@code adapter.out.persistence} — not exposed as its own
 * application port. {@link IngestedItemJpaEntity} has no lifecycle
 * independent of its owning {@link BriefingJpaEntity}, so only {@code
 * BriefingRepositoryAdapter} ever calls this.
 */
interface IngestedItemJpaRepository extends JpaRepository<IngestedItemJpaEntity, Long> {

	List<IngestedItemJpaEntity> findByBriefingId(Long briefingId);

}
