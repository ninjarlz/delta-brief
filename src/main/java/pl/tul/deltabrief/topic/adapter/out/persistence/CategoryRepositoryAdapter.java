package pl.tul.deltabrief.topic.adapter.out.persistence;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pl.tul.deltabrief.topic.application.port.out.CategoryRepository;
import pl.tul.deltabrief.topic.domain.Category;
import pl.tul.deltabrief.topic.domain.CategoryId;

@Component
@RequiredArgsConstructor
class CategoryRepositoryAdapter implements CategoryRepository {

	private final CategoryJpaRepository jpaRepository;
	private final CategoryEntityMapper mapper;

	@Override
	public List<Category> findAll() {
		return jpaRepository.findAll().stream().map(mapper::toDomain).toList();
	}

	@Override
	public boolean existsById(CategoryId id) {
		return jpaRepository.existsById(id.value());
	}

}
