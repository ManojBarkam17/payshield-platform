package com.payshield.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments", indexes = {
    @Index(name = "idx_payment_status", columnList = "status"),
    @Index(name = "idx_payment_merchant", columnList = "merchantId"),
    @Index(name = "idx_payment_created", columnList = "createdAt")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 34)
    private String senderAccountId;

    @Column(nullable = false, length = 34)
    private String receiverAccountId;

    @Column(nullable = false)
    private String merchantId;

    @Column(nullable = false, length = 100)
    private String merchantName;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentMethod paymentMethod;

    @Column(length = 500)
    private String description;

    private Integer fraudScore;

    @Column(length = 50)
    private String fraudReason;

    private boolean flaggedForReview;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant processedAt;

    @Column(length = 45)
    private String sourceIp;

    @Column(length = 2)
    private String sourceCountry;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = PaymentStatus.PENDING;
        }
    }

    public enum PaymentStatus {
        PENDING, PROCESSING, APPROVED, DECLINED, FLAGGED, REFUNDED
    }

    public enum PaymentMethod {
        CREDIT_CARD, DEBIT_CARD, BANK_TRANSFER, WALLET, ACH
    }
}
