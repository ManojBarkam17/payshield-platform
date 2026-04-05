package com.payshield.service;

import com.payshield.dto.PaymentDTO;
import com.payshield.model.FraudRule;
import com.payshield.model.Payment;
import com.payshield.repository.FraudRuleRepository;
import com.payshield.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Rule-based fraud detection engine that evaluates incoming payments
 * against configurable fraud rules and assigns a composite risk score.
 *
 * Scoring: 0-30 = low risk, 31-60 = medium, 61-100 = high (auto-decline)
 * Threshold of 70+ triggers automatic flagging for manual review.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FraudDetectionService {

    private static final int FLAG_THRESHOLD = 70;
    private static final int AUTO_DECLINE_THRESHOLD = 90;
    private static final String VELOCITY_CACHE_PREFIX = "velocity:";

    private final FraudRuleRepository fraudRuleRepository;
    private final PaymentRepository paymentRepository;
    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Evaluates a payment against all active fraud rules.
     * Returns a FraudCheckResult with the total score and triggered rules.
     */
    public PaymentDTO.FraudCheckResult evaluatePayment(Payment payment) {
        List<FraudRule> activeRules = fraudRuleRepository.findByActiveTrue();
        List<String> triggeredRules = new ArrayList<>();
        int totalScore = 0;
        String primaryReason = null;

        for (FraudRule rule : activeRules) {
            int ruleScore = evaluateRule(rule, payment);
            if (ruleScore > 0) {
                totalScore += ruleScore;
                triggeredRules.add(rule.getRuleCode() + " (+" + ruleScore + ")");
                if (primaryReason == null) {
                    primaryReason = rule.getDescription();
                }
            }
        }

        totalScore = Math.min(totalScore, 100);
        boolean flagged = totalScore >= FLAG_THRESHOLD;

        log.info("Fraud evaluation for payment {}: score={}, flagged={}, rules={}",
                payment.getId(), totalScore, flagged, triggeredRules);

        return PaymentDTO.FraudCheckResult.builder()
                .paymentId(payment.getId())
                .totalScore(totalScore)
                .flagged(flagged)
                .primaryReason(primaryReason)
                .triggeredRules(triggeredRules)
                .build();
    }

    /**
     * Determines if a payment should be auto-declined based on fraud score.
     */
    public boolean shouldAutoDecline(int fraudScore) {
        return fraudScore >= AUTO_DECLINE_THRESHOLD;
    }

    private int evaluateRule(FraudRule rule, Payment payment) {
        return switch (rule.getRuleType()) {
            case HIGH_AMOUNT -> evaluateHighAmount(rule, payment);
            case VELOCITY_CHECK -> evaluateVelocity(rule, payment);
            case GEO_ANOMALY -> evaluateGeoAnomaly(rule, payment);
            case UNUSUAL_HOUR -> evaluateUnusualHour(rule, payment);
            case CROSS_BORDER -> evaluateCrossBorder(rule, payment);
            case MERCHANT_BLACKLIST -> 0; // handled externally
        };
    }

    private int evaluateHighAmount(FraudRule rule, Payment payment) {
        if (rule.getThresholdAmount() != null &&
            payment.getAmount().compareTo(rule.getThresholdAmount()) > 0) {
            return rule.getScoreWeight() != null ? rule.getScoreWeight() : 25;
        }
        return 0;
    }

    private int evaluateVelocity(FraudRule rule, Payment payment) {
        String cacheKey = VELOCITY_CACHE_PREFIX + payment.getSenderAccountId();
        String cached = redisTemplate.opsForValue().get(cacheKey);

        int recentCount = cached != null ? Integer.parseInt(cached) + 1 : 1;
        redisTemplate.opsForValue().set(cacheKey, String.valueOf(recentCount),
                rule.getTimeWindowMinutes() != null ? rule.getTimeWindowMinutes() : 60,
                TimeUnit.MINUTES);

        if (rule.getThresholdCount() != null && recentCount > rule.getThresholdCount()) {
            return rule.getScoreWeight() != null ? rule.getScoreWeight() : 30;
        }
        return 0;
    }

    private int evaluateGeoAnomaly(FraudRule rule, Payment payment) {
        if (payment.getSourceCountry() == null) return 0;

        Payment lastTransaction = paymentRepository
                .findLastTransactionByAccount(payment.getSenderAccountId());

        if (lastTransaction != null && lastTransaction.getSourceCountry() != null &&
            !lastTransaction.getSourceCountry().equals(payment.getSourceCountry())) {

            long minutesSinceLast = ChronoUnit.MINUTES.between(
                    lastTransaction.getCreatedAt(), Instant.now());

            // Country change within 2 hours is suspicious
            if (minutesSinceLast < 120) {
                return rule.getScoreWeight() != null ? rule.getScoreWeight() : 35;
            }
        }
        return 0;
    }

    private int evaluateUnusualHour(FraudRule rule, Payment payment) {
        int hour = payment.getCreatedAt().atZone(java.time.ZoneOffset.UTC).getHour();
        // Transactions between 1 AM and 5 AM UTC are flagged
        if (hour >= 1 && hour <= 5) {
            return rule.getScoreWeight() != null ? rule.getScoreWeight() : 15;
        }
        return 0;
    }

    private int evaluateCrossBorder(FraudRule rule, Payment payment) {
        if (payment.getSourceCountry() != null && !"US".equals(payment.getSourceCountry())) {
            BigDecimal threshold = rule.getThresholdAmount() != null
                    ? rule.getThresholdAmount() : new BigDecimal("5000");
            if (payment.getAmount().compareTo(threshold) > 0) {
                return rule.getScoreWeight() != null ? rule.getScoreWeight() : 20;
            }
        }
        return 0;
    }
}
