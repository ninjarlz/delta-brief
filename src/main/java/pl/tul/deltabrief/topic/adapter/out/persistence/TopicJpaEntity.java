package pl.tul.deltabrief.topic.adapter.out.persistence;

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
import pl.tul.deltabrief.topic.domain.Frequency;
import pl.tul.deltabrief.topic.domain.ScheduledRunStatus;

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

	private String description;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Frequency frequency;

	@Column(name = "preferred_hour")
	private Integer preferredHour;

	@Column(name = "next_due_at")
	private Instant nextDueAt;

	@Column(name = "last_scheduled_attempt_at")
	private Instant lastScheduledAttemptAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "last_scheduled_status")
	private ScheduledRunStatus lastScheduledStatus;

}
