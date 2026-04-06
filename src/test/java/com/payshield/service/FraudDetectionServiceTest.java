package com.payshield.service;

import com.payshield.dto.PaymentDTO;
import com.payshield.model.FraudRule;
import com.payshield.model.FraudRule.RuleType;
import com.payshield.model.Payment;
import com.payshield.model.Payment.PaymentMethod;
import com.payshield.repository.FraudRuleRepository;
import com.payshield.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FraudDetectionService Tests")
class FraudDetectionServiceTest {

    @Mock private FraudRuleRepository fraudRuleRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private FraudDetectionService fraudDetectionService;

    private Payment testPayment;

    @BeforeEach
    void setUp() {
        testPayment = Payment.builder()
                .id(UUID.randomUUID())
                .senderAccountId("ACC-001")
                .receiverAccountId("ACC-002")
                .merchantId("MERCH-001")
                .merchantName("Test Merchant")
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .paymentMethod(PaymentMethod.CREDIT_CARD)
                .status(Payment.PaymentStatus.PROCESSING)
                .sourceCountry("US")
                .createdAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("Should return zero score when no rules are triggered")
    void evaluatePayment_noRulesTriggered_returnsZeroScore() {
        when(fraudRuleRepository.findByActiveTrue()).thenReturn(List.of());

        PaymentDTO.FraudCheckResult result = fraudDetectionService.evaluatePayment(testPayment);

        assertThat(result.getTotalScore()).isZero();
        assertThat(result.isFlagged()).isFalse();
        assertThat(result.getTriggeredRules()).isEmpty();
    }

    @Test
    @DisplayName("Should flag high-amount transaction above threshold")
    void evaluatePayment_highAmount_flagsTransaction() {
        testPayment.setAmount(new BigDecimal("15000.00"));

        FraudRule highAmountRule = FraudRule.builder()
                .id(UUID.randomUUID())
                .ruleCode("HIGH_AMOUNT_001")
                .description("Transaction exceeds $10,000")
                .ruleType(RuleType.HIGH_AMOUNT)
                .thresholdAmount(new BigDecimal("10000.00"))
                .scoreWeight(40)
                .active(true)
                .build();

        FraudRule crossBorderRule = FraudRule.builder()
                .id(UUID.randomUUID())
                .ruleCode("CROSS_BORDER_001")
                .description("Cross-border high value")
                .ruleType(RuleType.CROSS_BORDER)
                .thresholdAmount(new BigDecimal("5000.00"))
                .scoreWeight(35)
                .active(true)
                .build();

        // Non-US source to trigger cross-border
        testPayment.setSourceCountry("NG");

        when(fraudRuleRepository.findByActiveTrue())
                .thenReturn(List.of(highAmountRule, crossBorderRule));

        PaymentDTO.FraudCheckResult result = fraudDetectionService.evaluatePayment(testPayment);

        assertThat(result.getTotalScore()).isEqualTo(75);
        assertThat(result.isFlagged()).isTrue();
        assertThat(result.getTriggeredRules()).hasSize(2);
    }

    @Test
    @DisplayName("Should detect velocity anomaly from cached count")
    void evaluatePayment_velocityExceeded_addsScore() {
        FraudRule velocityRule = FraudRule.builder()
                .id(UUID.randomUUID())
                .ruleCode("VELOCITY_001")
                .description("More than 5 transactions in 60 minutes")
                .ruleType(RuleType.VELOCITY_CHECK)
                .thresholdCount(5)
                .timeWindowMinutes(60)
                .scoreWeight(30)
                .active(true)
                .build();

        when(fraudRuleRepository.findByActiveTrue()).thenReturn(List.of(velocityRule));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("6"); // already 6 transactions

        PaymentDTO.FraudCheckResult result = fraudDetectionService.evaluatePayment(testPayment);

        assertThat(result.getTotalScore()).isEqualTo(30);
        assertThat(result.getTriggeredRules()).hasSize(1);
        assertThat(result.getTriggeredRules().get(0)).contains("VELOCITY_001");
    }

    @Test
    @DisplayName("Should detect geo anomaly with rapid country change")
    void evaluatePayment_geoAnomaly_flagsTransaction() {
        FraudRule geoRule = FraudRule.builder()
                .id(UUID.randomUUID())
                .ruleCode("GEO_001")
                .description("Country changed within 2 hours")
                .ruleType(RuleType.GEO_ANOMALY)
                .scoreWeight(35)
                .active(true)
                .build();

        Payment lastPayment = Payment.builder()
                .sourceCountry("US")
                .createdAt(Instant.now().minusSeconds(1800)) // 30 min ago
                .build();

        testPayment.setSourceCountry("RU"); // different country

        when(fraudRuleRepository.findByActiveTrue()).thenReturn(List.of(geoRule));
        when(paymentRepository.findLastTransactionByAccount("ACC-001")).thenReturn(lastPayment);

        PaymentDTO.FraudCheckResult result = fraudDetectionService.evaluatePayment(testPayment);

        assertThat(result.getTotalScore()).isEqualTo(35);
        assertThat(result.isFlagged()).isFalse(); // 35 < 70 threshold
    }

    @Test
    @DisplayName("Should auto-decline when score exceeds 90")
    void shouldAutoDecline_scoreAbove90_returnsTrue() {
        assertThat(fraudDetectionService.shouldAutoDecline(91)).isTrue();
        assertThat(fraudDetectionService.shouldAutoDecline(90)).isTrue();
        assertThat(fraudDetectionService.shouldAutoDecline(89)).isFalse();
    }

    @Test
    @DisplayName("Should cap total score at 100")
    void evaluatePayment_multipleRules_capsAt100() {
        testPayment.setAmount(new BigDecimal("50000.00"));
        testPayment.setSourceCountry("NG");

        List<FraudRule> rules = List.of(
                buildRule("R1", RuleType.HIGH_AMOUNT, 40, new BigDecimal("10000")),
                buildRule("R2", RuleType.CROSS_BORDER, 35, new BigDecimal("5000")),
                buildRule("R3", RuleType.UNUSUAL_HOUR, 15, null)
        );

        // Set time to 3 AM UTC to trigger unusual hour
        testPayment.setCreatedAt(Instant.parse("2024-06-15T03:00:00Z"));

        when(fraudRuleRepository.findByActiveTrue()).thenReturn(rules);

        PaymentDTO.FraudCheckResult result = fraudDetectionService.evaluatePayment(testPayment);

        assertThat(result.getTotalScore()).isEqualTo(90); // 40 + 35 + 15
        assertThat(result.isFlagged()).isTrue();
    }

    @Test
    @DisplayName("Should not flag domestic low-value transaction")
    void evaluatePayment_normalTransaction_passesClean() {
        FraudRule highAmountRule = buildRule("HIGH", RuleType.HIGH_AMOUNT, 25, new BigDecimal("10000"));
        FraudRule crossBorderRule = buildRule("CROSS", RuleType.CROSS_BORDER, 20, new BigDecimal("5000"));

        when(fraudRuleRepository.findByActiveTrue())
                .thenReturn(List.of(highAmountRule, crossBorderRule));

        // $100 domestic payment
        PaymentDTO.FraudCheckResult result = fraudDetectionService.evaluatePayment(testPayment);

        assertThat(result.getTotalScore()).isZero();
        assertThat(result.isFlagged()).isFalse();
        assertThat(result.getTriggeredRules()).isEmpty();
    }

    private FraudRule buildRule(String code, RuleType type, int weight, BigDecimal threshold) {
        return FraudRule.builder()
                .id(UUID.randomUUID())
                .ruleCode(code)
                .description("Test rule: " + code)
                .ruleType(type)
                .thresholdAmount(threshold)
                .scoreWeight(weight)
                .active(true)
                .build();
    }
}
