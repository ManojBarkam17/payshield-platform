package com.payshield.dto;

import com.payshield.model.Payment;
import jakarta.validation.constraints.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class PaymentDTO {

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class CreateRequest {

        @NotBlank(message = "Sender account is required")
        private String senderAccountId;

        @NotBlank(message = "Receiver account is required")
        private String receiverAccountId;

        @NotBlank(message = "Merchant ID is required")
        private String merchantId;

        @NotBlank(message = "Merchant name is required")
        private String merchantName;

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
        @DecimalMax(value = "1000000.00", message = "Amount exceeds maximum limit")
        private BigDecimal amount;

        @NotBlank @Size(min = 3, max = 3)
        private String currency;

        private Payment.PaymentMethod paymentMethod;
        private String description;
        private String sourceIp;
        private String sourceCountry;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class Response {
        private UUID id;
        private String senderAccountId;
        private String receiverAccountId;
        private String merchantName;
        private BigDecimal amount;
        private String currency;
        private Payment.PaymentStatus status;
        private Payment.PaymentMethod paymentMethod;
        private Integer fraudScore;
        private boolean flaggedForReview;
        private Instant createdAt;
        private Instant processedAt;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class FraudCheckResult {
        private UUID paymentId;
        private int totalScore;
        private boolean flagged;
        private String primaryReason;
        private java.util.List<String> triggeredRules;
    }
}
