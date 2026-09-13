package pl.tul.deltabrief.topic.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA mapping for the {@code sources} table. No repository/adapter reads
 * this yet — see {@link pl.tul.deltabrief.topic.domain.Source}'s doc
 * comment.
 */
@Entity
@Table(name = "sources")
public class SourceJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "category_id", nullable = false)
	private Long categoryId;

	private String name;

	@Column(name = "feed_url", nullable = false)
	private String feedUrl;

	protected SourceJpaEntity() {
	}

	public SourceJpaEntity(Long id, Long categoryId, String name, String feedUrl) {
		this.id = id;
		this.categoryId = categoryId;
		this.name = name;
		this.feedUrl = feedUrl;
	}

	public Long getId() {
		return id;
	}

	public Long getCategoryId() {
		return categoryId;
	}

	public String getName() {
		return name;
	}

	public String getFeedUrl() {
		return feedUrl;
	}

}
