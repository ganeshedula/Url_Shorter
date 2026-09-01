package com.url.shortener.repo;

import com.url.shortener.models.OtpPurpose;
import com.url.shortener.models.OtpVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OtpVerificationRepository extends JpaRepository<OtpVerification, UUID> {
    Optional<OtpVerification> findTopByEmailAndPurposeOrderByCreatedAtDesc(String email, OtpPurpose purpose);
    List<OtpVerification> findByEmailAndPurposeAndConsumedAtIsNullAndInvalidatedAtIsNull(String email, OtpPurpose purpose);
    Optional<OtpVerification> findTopByEmailAndPurposeAndResetTokenUsedAtIsNullOrderByCreatedAtDesc(String email, OtpPurpose purpose);
}
