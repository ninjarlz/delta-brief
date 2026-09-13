package pl.tul.deltabrief.topic.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface CategoryJpaRepository extends JpaRepository<CategoryJpaEntity, Long> {
}
