package pl.tul.deltabrief.briefing.adapter.out.persistence;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pl.tul.deltabrief.briefing.application.port.out.FeedSource;
import pl.tul.deltabrief.briefing.application.port.out.FeedSourceCatalog;
import pl.tul.deltabrief.topic.domain.CategoryId;

@Component
@RequiredArgsConstructor
class FeedSourceCatalogAdapter implements FeedSourceCatalog {

	private final FeedSourceJpaRepository jpaRepository;
	private final FeedSourceEntityMapper mapper;

	@Override
	public List<FeedSource> findByCategoryId(CategoryId categoryId) {
		return jpaRepository.findByCategoryId(categoryId.value()).stream().map(mapper::toDomain).toList();
	}

}
