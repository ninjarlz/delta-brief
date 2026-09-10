package pl.tul.deltabrief.config;

import com.github.dockerjava.api.model.HostConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Provisions a Testcontainers-backed PostgreSQL {@link DataSource} for
 * integration tests, replacing the application's default (env-var-driven)
 * datasource. Import this into any {@code @SpringBootTest} that needs a
 * real database — it is written once here, not duplicated per test class.
 *
 * <p>Two modes, selected declaratively by the {@code CI} Spring profile
 * (activated in {@code build.gradle}'s {@code test} task via a system
 * property, conditional on the {@code CI} environment variable — the only
 * place that reads it):
 * <ul>
 *   <li><b>{@code CI} profile</b>: a standard bridge-network
 *       {@link PostgreSQLContainer} with a dynamic port; Ryuk (enabled by
 *       default) reaps it, matching GitHub Actions' normal Docker setup.</li>
 *   <li><b>no {@code CI} profile (local)</b>: a {@link GenericContainer}
 *       with Docker host network mode and a fixed port, cleaned up via a
 *       JVM shutdown hook. Required on this machine — its corporate VPN
 *       breaks Docker's default bridge networking (TCP handshakes complete
 *       but the Postgres protocol connection gets reset before completing).
 *       Ryuk is disabled for local runs (see {@code build.gradle}) since it
 *       doesn't reliably manage host-network containers.</li>
 * </ul>
 *
 * <p>Both beans are {@code @Primary}, overriding the application's default
 * datasource bean for JPA and Flyway alike, without needing to exclude
 * {@code DataSourceAutoConfiguration}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersDatasourceConfig {

	private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:17");
	private static final int FIXED_LOCAL_PORT = 32785;
	private static final String DB_NAME = "deltabrief_test";
	private static final String DB_USER = "deltabrief";
	private static final String DB_PASSWORD = "deltabrief";

	@Bean
	@Primary
	@Profile("CI")
	public DataSource ciDataSource() {
		PostgreSQLContainer container = new PostgreSQLContainer(POSTGRES_IMAGE)
				.withDatabaseName(DB_NAME)
				.withUsername(DB_USER)
				.withPassword(DB_PASSWORD);
		container.start();
		return buildDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword());
	}

	@Bean
	@Primary
	@Profile("!CI")
	public DataSource localDataSource() {
		GenericContainer<?> container = new GenericContainer<>(POSTGRES_IMAGE)
				.withEnv("POSTGRES_DB", DB_NAME)
				.withEnv("POSTGRES_USER", DB_USER)
				.withEnv("POSTGRES_PASSWORD", DB_PASSWORD)
				.withEnv("PGPORT", String.valueOf(FIXED_LOCAL_PORT))
				.withCommand("postgres", "-c", "port=" + FIXED_LOCAL_PORT)
				.waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2))
				.withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(new HostConfig().withNetworkMode("host")));
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (container.isRunning()) {
				container.stop();
			}
		}));
		container.start();
		String url = "jdbc:postgresql://localhost:%d/%s".formatted(FIXED_LOCAL_PORT, DB_NAME);
		return buildDataSource(url, DB_USER, DB_PASSWORD);
	}

	private DataSource buildDataSource(String jdbcUrl, String username, String password) {
		HikariConfig hikariConfig = new HikariConfig();
		hikariConfig.setJdbcUrl(jdbcUrl);
		hikariConfig.setUsername(username);
		hikariConfig.setPassword(password);
		hikariConfig.setDriverClassName("org.postgresql.Driver");
		return new HikariDataSource(hikariConfig);
	}

}
