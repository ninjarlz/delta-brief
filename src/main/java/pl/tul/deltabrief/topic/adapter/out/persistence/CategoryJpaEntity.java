package pl.tul.deltabrief.topic.adapter.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA mapping for the {@code categories} table, kept separate from the
 * domain {@link pl.tul.deltabrief.topic.domain.Category} so the domain
 * stays a plain object with no persistence-framework dependency.
 */
@Entity
@Table(name = "categories")
public class CategoryJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String name;

	protected CategoryJpaEntity() {
	}

	public CategoryJpaEntity(Long id, String name) {
		this.id = id;
		this.name = name;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

}
