package com.payshield.service;

import com.payshield.dto.PaymentDTO;
import com.payshield.exception.PaymentDeclinedException;
import com.payshield.exception.PaymentNotFoundException;
import com.payshield.kafka.PaymentEventProducer;
import com.payshield.model.Payment;
import com.payshield.model.Payment.PaymentStatus;
import com.payshield.repository.PaymentRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private static final String PAYMENT_CACHE_PREFIX = "payment:";
    private static final long CACHE_TTL_MINUTES = 30;

    private final PaymentRepository paymentRepository;
    private final FraudDetectionService fraudDetectionService;
    private final PaymentEventProducer eventProducer;
    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Processes a new payment through the fraud detection pipeline.
     * 
     * Flow: Validate → Create → Fraud Check → Approve/Decline → Publish Event
     */
    @Transactional
    @CircuitBreaker(name = "paymentProcessing", fallbackMethod = "processPaymentFallback")
    public PaymentDTO.Response processPayment(PaymentDTO.CreateRequest request) {
        log.info("Processing payment: merchant={}, amount={} {}",
                request.getMerchantName(), request.getAmount(), request.getCurrency());

        // Build and persist the payment
        Payment payment = Payment.builder()
                .senderAccountId(request.getSenderAccountId())
                .receiverAccountId(request.getReceiverAccountId())
                .merchantId(request.getMerchantId())
                .merchantName(request.getMerchantName())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .paymentMethod(request.getPaymentMethod())
                .description(request.getDescription())
                .sourceIp(request.getSourceIp())
                .sourceCountry(request.getSourceCountry())
                .status(PaymentStatus.PROCESSING)
                .build();

        payment = paymentRepository.save(payment);

        // Run fraud detection
        PaymentDTO.FraudCheckResult fraudResult = fraudDetectionService.evaluatePayment(payment);
        payment.setFraudScore(fraudResult.getTotalScore());
        payment.setFlaggedForReview(fraudResult.isFlagged());

        if (fraudResult.getPrimaryReason() != null) {
            payment.setFraudReason(truncate(fraudResult.getPrimaryReason(), 50));
        }

        // Determine outcome
        if (fraudDetectionService.shouldAutoDecline(fraudResult.getTotalScore())) {
            payment.setStatus(PaymentStatus.DECLINED);
            log.warn("Payment {} auto-declined: fraudScore={}", payment.getId(), fraudResult.getTotalScore());
        } else if (fraudResult.isFlagged()) {
            payment.setStatus(PaymentStatus.FLAGGED);
            log.info("Payment {} flagged for review: fraudScore={}", payment.getId(), fraudResult.getTotalScore());
        } else {
            payment.setStatus(PaymentStatus.APPROVED);
            payment.setProcessedAt(Instant.now());
        }

        payment = paymentRepository.save(payment);

        // Publish event to Kafka
        eventProducer.publishPaymentEvent(payment);

        // Cache the result
        cachePayment(payment);

        return toResponse(payment);
    }

    public PaymentDTO.Response getPaymentById(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        return toResponse(payment);
    }

    public Page<PaymentDTO.Response> getPaymentsByStatus(PaymentStatus status, Pageable pageable) {
        return paymentRepository.findByStatus(status, pageable).map(this::toResponse);
    }

    public Page<PaymentDTO.Response> getPaymentsByMerchant(String merchantId, Pageable pageable) {
        return paymentRepository.findByMerchantId(merchantId, pageable).map(this::toResponse);
    }

    @Transactional
    public PaymentDTO.Response approvePayment(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        if (payment.getStatus() != PaymentStatus.FLAGGED) {
            throw new IllegalStateException("Only flagged payments can be manually approved");
        }

        payment.setStatus(PaymentStatus.APPROVED);
        payment.setProcessedAt(Instant.now());
        payment.setFlaggedForReview(false);
        payment = paymentRepository.save(payment);

        eventProducer.publishPaymentEvent(payment);
        return toResponse(payment);
    }

    // Circuit breaker fallback
    private PaymentDTO.Response processPaymentFallback(PaymentDTO.CreateRequest request, Throwable t) {
        log.error("Payment processing circuit breaker triggered: {}", t.getMessage());
        return PaymentDTO.Response.builder()
                .status(PaymentStatus.PENDING)
                .build();
    }

    private void cachePayment(Payment payment) {
        try {
            String key = PAYMENT_CACHE_PREFIX + payment.getId();
            redisTemplate.opsForValue().set(key, payment.getStatus().name(),
                    CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Failed to cache payment {}: {}", payment.getId(), e.getMessage());
        }
    }

    private PaymentDTO.Response toResponse(Payment p) {
        return PaymentDTO.Response.builder()
                .id(p.getId())
                .senderAccountId(p.getSenderAccountId())
                .receiverAccountId(p.getReceiverAccountId())
                .merchantName(p.getMerchantName())
                .amount(p.getAmount())
                .currency(p.getCurrency())
                .status(p.getStatus())
                .paymentMethod(p.getPaymentMethod())
                .fraudScore(p.getFraudScore())
                .flaggedForReview(p.isFlaggedForReview())
                .createdAt(p.getCreatedAt())
                .processedAt(p.getProcessedAt())
                .build();
    }

    private String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) : s;
    }
}
