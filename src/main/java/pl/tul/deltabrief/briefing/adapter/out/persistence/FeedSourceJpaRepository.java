package pl.tul.deltabrief.briefing.adapter.out.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface FeedSourceJpaRepository extends JpaRepository<FeedSourceJpaEntity, Long> {

	List<FeedSourceJpaEntity> findByCategoryId(Long categoryId);

}
