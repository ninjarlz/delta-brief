package pl.tul.deltabrief.topic.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import pl.tul.deltabrief.topic.domain.Frequency;

interface TopicJpaRepository extends JpaRepository<TopicJpaEntity, Long> {

	List<TopicJpaEntity> findAllByUserId(Long userId);

	boolean existsByUserIdAndNameIgnoreCase(Long userId, String name);

	long countByUserId(Long userId);

	/**
	 * Spring Data interface projection — selects only {@code name}/{@code
	 * categoryId}/{@code description}/{@code emailEnabled}/{@code frequency},
	 * not the full row; this is an ownership check + summary lookup, not a
	 * full topic read.
	 */
	Optional<TopicNameAndCategoryView> findByIdAndUserId(Long id, Long userId);

	interface TopicNameAndCategoryView {

		String getName();

		Long getCategoryId();

		String getDescription();

		boolean getEmailEnabled();

		Frequency getFrequency();

	}

	/**
	 * Full-row owner-scoped fetch for the schedule edit flow — a distinct
	 * method from {@link #findByIdAndUserId(Long, Long)} above (which
	 * returns only the summary projection), not an overload of it. An
	 * explicit {@code @Query} sidesteps Spring Data's derived-query name
	 * parsing, so this method name doesn't need to encode the query itself.
	 */
	@Query("select t from TopicJpaEntity t where t.id = :id and t.userId = :userId")
	Optional<TopicJpaEntity> findEntityByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

	/**
	 * Owner-scoped at the query level (not fetch-then-check) — returns the
	 * number of rows actually deleted, so the caller can distinguish
	 * "deleted" from "not found/not owned" without a separate query.
	 */
	@Transactional
	long deleteByIdAndUserId(Long id, Long userId);

	/**
	 * Backs the scheduler's poll query (FR-009): every topic whose {@code
	 * nextDueAt} is in the past. Every {@link pl.tul.deltabrief.topic.domain.Frequency}
	 * always produces a {@code nextDueAt} via {@code ScheduleCalculator}, so
	 * the {@code is not null} check is a defensive guard, not the primary
	 * filtering mechanism — see the index {@code topics_next_due_at_idx}
	 * (V13/V15 migrations).
	 */
	@Query("select t from TopicJpaEntity t where t.nextDueAt is not null and t.nextDueAt <= :now")
	List<TopicJpaEntity> findDueForScheduledGeneration(@Param("now") Instant now);

}
