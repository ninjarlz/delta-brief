package pl.tul.deltabrief.briefing.adapter.out.persistence;

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
 * JPA mapping for the {@code ingested_items} table — a plain {@code
 * briefing_id} foreign-key column, no JPA relationship annotation, matching
 * this codebase's flat entity style. Always written and read alongside its
 * owning {@link BriefingJpaEntity} row (see {@code BriefingRepositoryAdapter}).
 */
@Entity
@Table(name = "ingested_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class IngestedItemJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "briefing_id", nullable = false)
	private Long briefingId;

	@Column(name = "source_name", nullable = false)
	private String sourceName;

	@Column(nullable = false)
	private String title;

	@Column(nullable = false)
	private String link;

	@Column(name = "published_at")
	private Instant publishedAt;

	@Column(name = "fetched_at", nullable = false)
	private Instant fetchedAt;

}
