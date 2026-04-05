package com.payshield.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventConsumer {

    private final ObjectMapper objectMapper;

    /**
     * Listens to payment events for downstream analytics and notifications.
     * Uses manual acknowledgment to ensure at-least-once delivery.
     */
    @KafkaListener(
        topics = "payment-events",
        groupId = "payshield-analytics",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handlePaymentEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            JsonNode event = objectMapper.readTree(record.value());
            String eventType = event.get("eventType").asText();
            String paymentId = event.get("paymentId").asText();

            log.info("Received payment event: type={}, paymentId={}, partition={}, offset={}",
                    eventType, paymentId, record.partition(), record.offset());

            switch (eventType) {
                case "PAYMENT_APPROVED" -> processApproved(event);
                case "PAYMENT_DECLINED" -> processDeclined(event);
                case "PAYMENT_FLAGGED" -> processFlagged(event);
                default -> log.debug("Unhandled event type: {}", eventType);
            }

            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing payment event at offset {}: {}",
                    record.offset(), e.getMessage());
            // Don't ack — message will be redelivered
        }
    }

    @KafkaListener(
        topics = "fraud-alerts",
        groupId = "payshield-fraud-monitor"
    )
    public void handleFraudAlert(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            JsonNode event = objectMapper.readTree(record.value());
            int fraudScore = event.get("fraudScore").asInt();
            String paymentId = event.get("paymentId").asText();

            log.warn("FRAUD ALERT: paymentId={}, fraudScore={}, merchant={}",
                    paymentId, fraudScore, event.get("merchantId").asText());

            // In production: trigger PagerDuty/Slack notification
            // notificationService.sendFraudAlert(paymentId, fraudScore);

            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing fraud alert: {}", e.getMessage());
        }
    }

    private void processApproved(JsonNode event) {
        // Update analytics counters, notify merchant, etc.
        log.debug("Processing approved payment: {}", event.get("paymentId").asText());
    }

    private void processDeclined(JsonNode event) {
        // Log for compliance, notify customer support
        log.info("Payment declined: id={}, fraudScore={}",
                event.get("paymentId").asText(), event.get("fraudScore").asInt());
    }

    private void processFlagged(JsonNode event) {
        // Queue for manual review team
        log.info("Payment queued for review: id={}", event.get("paymentId").asText());
    }
}
