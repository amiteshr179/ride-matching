package com.ridematching.trip;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripRepository extends JpaRepository<Trip, UUID> {

    Optional<Trip> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Trip t where t.id = :id")
    Optional<Trip> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Trips waiting for a matching round. No lock here; each round takes a
     * row lock and re-checks, so two pollers can not both act on one trip.
     */
    @Query(value = """
            select id from trips
            where status = 'REQUESTED' and next_match_at <= :now
            order by next_match_at
            limit :limit
            """, nativeQuery = true)
    List<UUID> findTripIdsDueForMatching(@Param("now") Instant now, @Param("limit") int limit);

    List<Trip> findByStatusIn(Collection<TripStatus> statuses);
}
