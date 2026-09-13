package pl.tul.deltabrief.topic.application.port.out;

import java.util.List;
import pl.tul.deltabrief.topic.domain.Category;
import pl.tul.deltabrief.topic.domain.CategoryId;

/**
 * Port the application layer depends on for {@link Category} reads,
 * implemented by the persistence adapter — keeps {@code application} free
 * of JPA imports.
 */
public interface CategoryRepository {

	List<Category> findAll();

	boolean existsById(CategoryId id);

}
