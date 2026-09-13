package pl.tul.deltabrief.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import javax.sql.DataSource;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.testcontainers.DockerClientFactory;
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
 *       JVM shutdown hook. Required when a VPN breaks Docker's default
 *       bridge networking (TCP handshakes complete but the Postgres
 *       protocol connection gets reset before completing). Ryuk is
 *       disabled for local runs (see {@code build.gradle}) since it
 *       doesn't reliably manage host-network containers.</li>
 * </ul>
 *
 * <p>Both beans are {@code @Primary}, overriding the application's default
 * datasource bean for JPA and Flyway alike, without needing to exclude
 * {@code DataSourceAutoConfiguration}.
 */
@TestConfiguration
@Log4j2
public class TestcontainersDatasourceConfig {

	private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:17");
	private static final int FIXED_LOCAL_PORT = 32785;
	private static final String DB_NAME = "deltabrief_test";
	private static final String DB_USER = "deltabrief";
	private static final String DB_PASSWORD = "deltabrief";
	private static final String LOCAL_CONTAINER_NAME = "deltabrief-local-testcontainers-postgres";

	@Bean
	@Primary
	@Profile("CI")
	public DataSource ciDataSource() {
		log.info(">>> Starting CI Testcontainers PostgreSQL (bridge network, dynamic port)...");
		PostgreSQLContainer container = new PostgreSQLContainer(POSTGRES_IMAGE)
				.withDatabaseName(DB_NAME)
				.withUsername(DB_USER)
				.withPassword(DB_PASSWORD);
		container.start();
		String jdbcUrl = container.getJdbcUrl();
		log.info(">>> Creating CI datasource using Testcontainers JDBC URL: {}", jdbcUrl);
		return buildDataSource(jdbcUrl, container.getUsername(), container.getPassword());
	}

	@Bean
	@Primary
	@Profile("!CI")
	public DataSource localDataSource() {
		String jdbcUrl = "jdbc:postgresql://localhost:%d/%s".formatted(FIXED_LOCAL_PORT, DB_NAME);
		// Some test classes need a distinct Spring context from the shared one
		// (e.g. @EnableWireMock always forces a new context, regardless of
		// properties) — when that happens mid-run, this bean method runs
		// again. Reusing an already-RUNNING container (rather than always
		// force-removing + recreating) is essential here: recreating would
		// rip the container out from under whichever other already-cached
		// context is still using it, corrupting its live connections.
		if (isLocalContainerRunning()) {
			log.info(">>> Reusing already-running local Testcontainers PostgreSQL (host network, fixed port {})...",
					FIXED_LOCAL_PORT);
			return buildDataSource(jdbcUrl, DB_USER, DB_PASSWORD);
		}
		log.info(">>> Starting local Testcontainers PostgreSQL (host network, fixed port {})...", FIXED_LOCAL_PORT);
		removeStaleLocalContainer();
		GenericContainer<?> container = new GenericContainer<>(POSTGRES_IMAGE)
				.withEnv("POSTGRES_DB", DB_NAME)
				.withEnv("POSTGRES_USER", DB_USER)
				.withEnv("POSTGRES_PASSWORD", DB_PASSWORD)
				.withEnv("PGPORT", String.valueOf(FIXED_LOCAL_PORT))
				.withCommand("postgres", "-c", "port=" + FIXED_LOCAL_PORT)
				.waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2))
				.withCreateContainerCmdModifier(cmd -> cmd.withName(LOCAL_CONTAINER_NAME)
						.withHostConfig(new HostConfig().withNetworkMode("host")));
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (container.isRunning()) {
				log.info(">>> Stopping local Testcontainers PostgreSQL...");
				container.stop();
			}
		}));
		container.start();
		log.info(">>> Creating local datasource using fixed host-network JDBC URL: {}", jdbcUrl);
		return buildDataSource(jdbcUrl, DB_USER, DB_PASSWORD);
	}

	private boolean isLocalContainerRunning() {
		DockerClient client = DockerClientFactory.instance().client();
		return !client.listContainersCmd().withShowAll(false).withNameFilter(List.of(LOCAL_CONTAINER_NAME)).exec()
				.isEmpty();
	}

	/**
	 * Self-heals a leaked local container from a prior JVM hard-kill (Ryuk is
	 * disabled for host-network mode, and a hard kill skips the shutdown hook
	 * below) — without this, a leaked container would keep {@link
	 * #FIXED_LOCAL_PORT} bound and fail every subsequent local test run until
	 * removed manually. Only reached when {@link #isLocalContainerRunning()}
	 * is false, so this never removes a container another context is using.
	 */
	private void removeStaleLocalContainer() {
		DockerClient client = DockerClientFactory.instance().client();
		List<Container> stale = client.listContainersCmd()
				.withShowAll(true)
				.withNameFilter(List.of(LOCAL_CONTAINER_NAME))
				.exec();
		for (Container container : stale) {
			log.info(">>> Removing stale local Testcontainers PostgreSQL container: {}", container.getId());
			client.removeContainerCmd(container.getId()).withForce(true).exec();
		}
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
