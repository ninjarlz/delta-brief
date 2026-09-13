package pl.tul.deltabrief.topic.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * JPA mapping for the {@code topics} table, kept separate from the domain
 * {@link pl.tul.deltabrief.topic.domain.Topic} so the domain stays a plain
 * object with no persistence-framework dependency.
 */
@Entity
@Table(name = "topics")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class TopicJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(nullable = false)
	private String name;

	@Column(name = "category_id", nullable = false)
	private Long categoryId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

}
