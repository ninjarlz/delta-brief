package pl.tul.deltabrief.topic.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

interface TopicJpaRepository extends JpaRepository<TopicJpaEntity, Long> {

	List<TopicJpaEntity> findAllByUserId(Long userId);

	boolean existsByUserIdAndNameIgnoreCase(Long userId, String name);

	long countByUserId(Long userId);

	/**
	 * Selects just {@code category_id} rather than loading the full entity —
	 * this is an ownership check + category lookup, not a full topic read.
	 */
	@Query("select t.categoryId from TopicJpaEntity t where t.id = ?1 and t.userId = ?2")
	Optional<Long> findCategoryIdByIdAndUserId(Long id, Long userId);

	/**
	 * Owner-scoped at the query level (not fetch-then-check) — returns the
	 * number of rows actually deleted, so the caller can distinguish
	 * "deleted" from "not found/not owned" without a separate query.
	 */
	@Transactional
	long deleteByIdAndUserId(Long id, Long userId);

}
