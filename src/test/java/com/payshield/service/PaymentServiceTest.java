package com.payshield.service;

import com.payshield.dto.PaymentDTO;
import com.payshield.exception.PaymentNotFoundException;
import com.payshield.kafka.PaymentEventProducer;
import com.payshield.model.Payment;
import com.payshield.model.Payment.PaymentStatus;
import com.payshield.repository.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService Tests")
class PaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private FraudDetectionService fraudDetectionService;
    @Mock private PaymentEventProducer eventProducer;
    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private PaymentService paymentService;

    @Test
    @DisplayName("Should approve payment with low fraud score")
    void processPayment_lowFraudScore_approvesPayment() {
        PaymentDTO.CreateRequest request = PaymentDTO.CreateRequest.builder()
                .senderAccountId("ACC-001")
                .receiverAccountId("ACC-002")
                .merchantId("MERCH-001")
                .merchantName("Coffee Shop")
                .amount(new BigDecimal("4.50"))
                .currency("USD")
                .build();

        Payment savedPayment = buildPayment(PaymentStatus.PROCESSING);

        when(paymentRepository.save(any(Payment.class))).thenReturn(savedPayment);
        when(fraudDetectionService.evaluatePayment(any())).thenReturn(
                PaymentDTO.FraudCheckResult.builder()
                        .totalScore(5).flagged(false).triggeredRules(java.util.List.of())
                        .build());
        when(fraudDetectionService.shouldAutoDecline(5)).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        PaymentDTO.Response response = paymentService.processPayment(request);

        assertThat(response).isNotNull();
        verify(eventProducer).publishPaymentEvent(any(Payment.class));
        verify(paymentRepository, times(2)).save(any(Payment.class));
    }

    @Test
    @DisplayName("Should throw exception for non-existent payment")
    void getPaymentById_notFound_throwsException() {
        UUID id = UUID.randomUUID();
        when(paymentRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getPaymentById(id))
                .isInstanceOf(PaymentNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    @DisplayName("Should approve flagged payment on manual review")
    void approvePayment_flaggedPayment_setsApproved() {
        UUID paymentId = UUID.randomUUID();
        Payment flagged = buildPayment(PaymentStatus.FLAGGED);
        flagged.setId(paymentId);

        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(flagged));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentDTO.Response result = paymentService.approvePayment(paymentId);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        verify(eventProducer).publishPaymentEvent(any());
    }

    @Test
    @DisplayName("Should reject approval of non-flagged payment")
    void approvePayment_notFlagged_throwsException() {
        UUID id = UUID.randomUUID();
        Payment approved = buildPayment(PaymentStatus.APPROVED);
        approved.setId(id);

        when(paymentRepository.findById(id)).thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> paymentService.approvePayment(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flagged");
    }

    private Payment buildPayment(PaymentStatus status) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .senderAccountId("ACC-001")
                .receiverAccountId("ACC-002")
                .merchantId("MERCH-001")
                .merchantName("Test Merchant")
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .status(status)
                .createdAt(Instant.now())
                .build();
    }
}
