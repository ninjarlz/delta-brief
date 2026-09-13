package pl.tul.deltabrief.briefing.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A {@code briefing}-owned, read-only JPA mapping onto the same {@code
 * sources} table {@code topic.adapter.out.persistence.SourceJpaEntity} also
 * maps — deliberately duplicated rather than shared, so {@code briefing}
 * never imports {@code topic}'s domain aggregates (see plan.md's Critical
 * Implementation Details). Cheap to duplicate: {@code sources} is a small,
 * migration-seeded reference table, never written to at runtime.
 */
@Entity
@Table(name = "sources")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class FeedSourceJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "category_id", nullable = false)
	private Long categoryId;

	private String name;

	@Column(name = "feed_url", nullable = false)
	private String feedUrl;

}
