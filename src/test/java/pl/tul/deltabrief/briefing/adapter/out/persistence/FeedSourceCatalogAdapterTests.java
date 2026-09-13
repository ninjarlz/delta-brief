package pl.tul.deltabrief.briefing.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import pl.tul.deltabrief.briefing.application.port.out.FeedSource;
import pl.tul.deltabrief.briefing.application.port.out.FeedSourceCatalog;
import pl.tul.deltabrief.config.SynchronousAsyncConfig;
import pl.tul.deltabrief.config.TestcontainersDatasourceConfig;
import pl.tul.deltabrief.topic.application.port.out.CategoryRepository;
import pl.tul.deltabrief.topic.domain.Category;
import pl.tul.deltabrief.topic.domain.CategoryId;

/**
 * Reads the real, V6-seeded {@code sources} table — no WireMock needed here,
 * this is a plain DB read (see plan.md's WireMock stubbing points).
 */
@SpringBootTest(properties = "app.async.email.enabled=false")
@Import({TestcontainersDatasourceConfig.class, SynchronousAsyncConfig.class})
class FeedSourceCatalogAdapterTests {

	@Autowired
	private FeedSourceCatalog feedSourceCatalog;

	@Autowired
	private CategoryRepository categoryRepository;

	@Test
	void returnsSeededSourcesForACategory() {
		Category worldNews = categoryRepository.findAll().stream()
				.filter(category -> category.name().equals("World News"))
				.findFirst()
				.orElseThrow();

		List<FeedSource> sources = feedSourceCatalog.findByCategoryId(worldNews.id());

		assertThat(sources).isNotEmpty();
		assertThat(sources).allSatisfy(source -> {
			assertThat(source.name()).isNotBlank();
			assertThat(source.feedUrl()).startsWith("http");
		});
	}

	@Test
	void returnsEmptyForACategoryWithNoSources() {
		CategoryId unknownCategoryId = new CategoryId(999_999L);

		assertThat(feedSourceCatalog.findByCategoryId(unknownCategoryId)).isEmpty();
	}

}
