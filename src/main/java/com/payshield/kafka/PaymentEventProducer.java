package com.payshield.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payshield.model.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventProducer {

    private static final String PAYMENT_TOPIC = "payment-events";
    private static final String FRAUD_ALERT_TOPIC = "fraud-alerts";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publishPaymentEvent(Payment payment) {
        try {
            Map<String, Object> event = Map.of(
                "eventType", resolveEventType(payment),
                "paymentId", payment.getId().toString(),
                "merchantId", payment.getMerchantId(),
                "amount", payment.getAmount().toString(),
                "currency", payment.getCurrency(),
                "status", payment.getStatus().name(),
                "fraudScore", payment.getFraudScore() != null ? payment.getFraudScore() : 0,
                "timestamp", payment.getCreatedAt().toString()
            );

            String payload = objectMapper.writeValueAsString(event);
            String key = payment.getMerchantId(); // partition by merchant

            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(PAYMENT_TOPIC, key, payload);

            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish payment event for {}: {}",
                            payment.getId(), ex.getMessage());
                } else {
                    log.debug("Payment event published: id={}, partition={}, offset={}",
                            payment.getId(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            });

            // Publish to fraud alerts topic if flagged
            if (payment.isFlaggedForReview() || payment.getStatus() == Payment.PaymentStatus.DECLINED) {
                publishFraudAlert(payment, payload);
            }

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payment event: {}", e.getMessage());
        }
    }

    private void publishFraudAlert(Payment payment, String payload) {
        kafkaTemplate.send(FRAUD_ALERT_TOPIC, payment.getId().toString(), payload);
        log.info("Fraud alert published for payment {}: score={}",
                payment.getId(), payment.getFraudScore());
    }

    private String resolveEventType(Payment payment) {
        return switch (payment.getStatus()) {
            case APPROVED -> "PAYMENT_APPROVED";
            case DECLINED -> "PAYMENT_DECLINED";
            case FLAGGED -> "PAYMENT_FLAGGED";
            case REFUNDED -> "PAYMENT_REFUNDED";
            default -> "PAYMENT_CREATED";
        };
    }
}
