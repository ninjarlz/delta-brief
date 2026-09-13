package pl.tul.deltabrief.topic.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * JPA mapping for the {@code topics} table, kept separate from the domain
 * {@link pl.tul.deltabrief.topic.domain.Topic} so the domain stays a plain
 * object with no persistence-framework dependency.
 */
@Entity
@Table(name = "topics")
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

	protected TopicJpaEntity() {
	}

	public TopicJpaEntity(Long id, Long userId, String name, Long categoryId, Instant createdAt) {
		this.id = id;
		this.userId = userId;
		this.name = name;
		this.categoryId = categoryId;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public String getName() {
		return name;
	}

	public Long getCategoryId() {
		return categoryId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
