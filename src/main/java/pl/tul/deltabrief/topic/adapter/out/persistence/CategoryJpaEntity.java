package pl.tul.deltabrief.topic.adapter.out.persistence;

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
 * JPA mapping for the {@code categories} table, kept separate from the
 * domain {@link pl.tul.deltabrief.topic.domain.Category} so the domain
 * stays a plain object with no persistence-framework dependency.
 */
@Entity
@Table(name = "categories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CategoryJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String name;

}
