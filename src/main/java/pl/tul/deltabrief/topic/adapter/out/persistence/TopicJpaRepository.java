package pl.tul.deltabrief.topic.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
	 * Owner-scoped at the query level (not fetch-then-check) — returns the
	 * number of rows actually deleted, so the caller can distinguish
	 * "deleted" from "not found/not owned" without a separate query.
	 */
	@Transactional
	long deleteByIdAndUserId(Long id, Long userId);

}
