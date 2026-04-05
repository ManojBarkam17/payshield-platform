package com.payshield.repository;

import com.payshield.model.Payment;
import com.payshield.model.Payment.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Page<Payment> findByStatus(PaymentStatus status, Pageable pageable);

    Page<Payment> findByMerchantId(String merchantId, Pageable pageable);

    List<Payment> findByFlaggedForReviewTrue();

    @Query("SELECT COUNT(p) FROM Payment p WHERE p.senderAccountId = :accountId " +
           "AND p.createdAt > :since AND p.status != 'DECLINED'")
    long countRecentTransactions(
        @Param("accountId") String accountId,
        @Param("since") Instant since
    );

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p " +
           "WHERE p.senderAccountId = :accountId " +
           "AND p.createdAt > :since AND p.status != 'DECLINED'")
    BigDecimal sumRecentTransactionAmounts(
        @Param("accountId") String accountId,
        @Param("since") Instant since
    );

    @Query("SELECT p FROM Payment p WHERE p.senderAccountId = :accountId " +
           "ORDER BY p.createdAt DESC LIMIT 1")
    Payment findLastTransactionByAccount(@Param("accountId") String accountId);

    @Query("SELECT p.status, COUNT(p) FROM Payment p " +
           "WHERE p.createdAt > :since GROUP BY p.status")
    List<Object[]> countByStatusSince(@Param("since") Instant since);
}
