package com.payshield.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "fraud_rules")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class FraudRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String ruleCode;

    @Column(nullable = false, length = 200)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RuleType ruleType;

    private BigDecimal thresholdAmount;

    private Integer thresholdCount;

    private Integer timeWindowMinutes;

    private Integer scoreWeight;

    @Column(nullable = false)
    private boolean active;

    public enum RuleType {
        HIGH_AMOUNT,
        VELOCITY_CHECK,
        GEO_ANOMALY,
        MERCHANT_BLACKLIST,
        UNUSUAL_HOUR,
        CROSS_BORDER
    }
}
