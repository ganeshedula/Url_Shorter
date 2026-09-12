package com.url.shortener.repo;

import com.url.shortener.models.ClickEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ClickEventRepository extends JpaRepository<ClickEvent, UUID> {
    List<ClickEvent> findTop20ByUrlMapping_IdOrderByAccessedAtDesc(UUID urlMappingId);
    List<ClickEvent> findByUrlMapping_IdOrderByAccessedAtAsc(UUID urlMappingId);

    @Query("SELECT cast(c.accessedAt as LocalDate), COUNT(c) " +
           "FROM ClickEvent c WHERE c.urlMapping.id = :urlMappingId " +
           "GROUP BY cast(c.accessedAt as LocalDate) " +
           "ORDER BY cast(c.accessedAt as LocalDate) ASC")
    List<Object[]> findDailyClickCountsRaw(@Param("urlMappingId") UUID urlMappingId);

    @Query("SELECT c.country, c.countryCode, c.region, c.city, c.latitude, c.longitude, c.timezone, COUNT(c) " +
           "FROM ClickEvent c WHERE c.urlMapping.id = :urlMappingId " +
           "GROUP BY c.country, c.countryCode, c.region, c.city, c.latitude, c.longitude, c.timezone " +
           "ORDER BY COUNT(c) DESC")
    List<Object[]> findTopLocationsRaw(@Param("urlMappingId") UUID urlMappingId, Pageable pageable);
}
