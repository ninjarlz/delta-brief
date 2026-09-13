package pl.tul.deltabrief;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

// EnableCaching backs bucket4j-spring-boot-starter's buckets with the
// Caffeine/JCache cache configured in application.properties.
// EnableAspectJAutoProxy is required for the @RateLimiting AOP interception
// on RegistrationController to actually run.
@SpringBootApplication
@EnableCaching
@EnableAspectJAutoProxy
public class DeltaBriefApplication {

	public static void main(String[] args) {
		SpringApplication.run(DeltaBriefApplication.class, args);
	}

}
