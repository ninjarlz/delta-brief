package pl.tul.deltabrief.topic.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface TopicJpaRepository extends JpaRepository<TopicJpaEntity, Long> {

	List<TopicJpaEntity> findAllByUserId(Long userId);

	boolean existsByUserIdAndNameIgnoreCase(Long userId, String name);

	long countByUserId(Long userId);

	/**
	 * Spring Data interface projection — selects only {@code name}/{@code
	 * categoryId}/{@code description}, not the full row; this is an
	 * ownership check + summary lookup, not a full topic read.
	 */
	Optional<TopicNameAndCategoryView> findByIdAndUserId(Long id, Long userId);

	interface TopicNameAndCategoryView {

		String getName();

		Long getCategoryId();

		String getDescription();

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

}
