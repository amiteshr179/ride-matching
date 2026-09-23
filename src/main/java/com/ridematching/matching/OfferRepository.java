package com.ridematching.matching;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface OfferRepository extends JpaRepository<Offer, UUID> {

    @Query("select o.driverId from Offer o where o.tripId = :tripId")
    Set<String> findDriverIdsOfferedForTrip(@Param("tripId") UUID tripId);

    Optional<Offer> findByTripIdAndStatus(UUID tripId, OfferStatus status);

    Optional<Offer> findFirstByDriverIdAndStatusOrderByCreatedAtDesc(String driverId, OfferStatus status);

    @Query("select o.tripId from Offer o where o.status = com.ridematching.matching.OfferStatus.PENDING and o.expiresAt <= :now")
    List<UUID> findTripIdsWithExpiredOffers(@Param("now") Instant now);
}
