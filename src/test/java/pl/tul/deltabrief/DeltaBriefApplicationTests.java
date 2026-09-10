package pl.tul.deltabrief;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import pl.tul.deltabrief.config.TestcontainersDatasourceConfig;

/**
 * A successful context load here proves the full datasource + Flyway + JPA
 * stack works against a real, ephemeral Postgres — not just that Spring
 * boots. See {@link TestcontainersDatasourceConfig} for how the container is
 * provisioned and CI/local mode selection (the {@code CI} Spring profile, set
 * by {@code build.gradle}'s {@code test} task).
 */
@SpringBootTest
@Import(TestcontainersDatasourceConfig.class)
class DeltaBriefApplicationTests {

	@Test
	void contextLoads() {
	}

}
