package pl.tul.deltabrief.topic.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import pl.tul.deltabrief.config.SynchronousAsyncConfig;
import pl.tul.deltabrief.config.TestcontainersDatasourceConfig;
import pl.tul.deltabrief.topic.application.port.out.CategoryRepository;
import pl.tul.deltabrief.topic.domain.Category;
import pl.tul.deltabrief.topic.domain.CategoryId;

/**
 * Reuses the exact same context shape as {@code DeltaBriefApplicationTests}
 * (same {@code @Import}) so Spring's test context cache shares one
 * Testcontainers-backed datasource — see {@link TestcontainersDatasourceConfig}'s
 * single-fixed-local-port constraint.
 *
 * <p>Also a regression guard on {@code V6__seed_categories_and_sources.sql}:
 * no {@code SourceRepository} port exists yet (nothing in this slice reads
 * individual sources), so source counts are checked via a plain
 * {@link JdbcTemplate} query rather than adding a production port solely
 * for this test.
 */
@SpringBootTest(properties = "app.async.email.enabled=false")
@Import({TestcontainersDatasourceConfig.class, SynchronousAsyncConfig.class})
class CategoryRepositoryAdapterTests {

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void seedMigrationLoadsTheFourPresetCategories() {
		var categories = categoryRepository.findAll();

		assertThat(categories).extracting(Category::name)
				.containsExactlyInAnyOrder("World News", "Technology", "Business & Finance", "Science");
	}

	@Test
	void seedMigrationLoadsAtLeastOneSourcePerCategory() {
		var categories = categoryRepository.findAll();

		for (Category category : categories) {
			Integer sourceCount = jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM sources WHERE category_id = ?", Integer.class, category.id().value());
			assertThat(sourceCount).as("source count for category %s", category.name()).isGreaterThan(0);
		}
	}

	@Test
	void existsByIdIsTrueForASeededCategoryAndFalseForAnUnknownId() {
		var anyCategory = categoryRepository.findAll().get(0);

		assertThat(categoryRepository.existsById(anyCategory.id())).isTrue();
		assertThat(categoryRepository.existsById(new CategoryId(999_999L))).isFalse();
	}

}
