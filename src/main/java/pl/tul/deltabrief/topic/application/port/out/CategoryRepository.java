package pl.tul.deltabrief.topic.application.port.out;

import java.util.List;
import java.util.Optional;
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

	/**
	 * Returns just the name, not the {@link Category} aggregate — used by
	 * {@code briefing} for its generation prompt without ever importing
	 * {@code topic}'s domain aggregates (see plan.md's Critical
	 * Implementation Details).
	 */
	Optional<String> findNameById(CategoryId id);

}
