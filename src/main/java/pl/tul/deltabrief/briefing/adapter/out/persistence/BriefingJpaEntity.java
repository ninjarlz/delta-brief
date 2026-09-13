package pl.tul.deltabrief.briefing.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import pl.tul.deltabrief.briefing.domain.BriefingType;

/**
 * JPA mapping for the {@code briefings} table, kept separate from the
 * domain {@link pl.tul.deltabrief.briefing.domain.Briefing} so the domain
 * stays a plain object with no persistence-framework dependency. Does not
 * model {@code ingestedItems} — that child collection is persisted and
 * loaded separately via {@link IngestedItemJpaEntity} (see
 * {@code BriefingRepositoryAdapter}), matching this codebase's flat,
 * relationship-annotation-free entity style.
 */
@Entity
@Table(name = "briefings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class BriefingJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "topic_id", nullable = false)
	private Long topicId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private BriefingType type;

	@Column(name = "generated_at", nullable = false)
	private Instant generatedAt;

	@Column(name = "key_changes", nullable = false)
	private String keyChanges;

	@Column(name = "trend_continuation", nullable = false)
	private String trendContinuation;

	@Column(name = "noise_speculation", nullable = false)
	private String noiseSpeculation;

	@Column(nullable = false)
	private String significance;

	@Column(nullable = false)
	private String uncertainties;

	@Column(name = "source_impact", nullable = false)
	private String sourceImpact;

}
