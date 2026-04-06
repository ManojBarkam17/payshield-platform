package com.payshield.controller;

import com.payshield.dto.PaymentDTO;
import com.payshield.model.Payment.PaymentStatus;
import com.payshield.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentDTO.Response> processPayment(
            @Valid @RequestBody PaymentDTO.CreateRequest request) {
        PaymentDTO.Response response = paymentService.processPayment(request);
        HttpStatus status = response.getStatus() == PaymentStatus.DECLINED
                ? HttpStatus.UNPROCESSABLE_ENTITY
                : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentDTO.Response> getPayment(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentService.getPaymentById(id));
    }

    @GetMapping
    public ResponseEntity<Page<PaymentDTO.Response>> getPaymentsByStatus(
            @RequestParam(required = false) PaymentStatus status,
            @PageableDefault(size = 20) Pageable pageable) {

        if (status != null) {
            return ResponseEntity.ok(paymentService.getPaymentsByStatus(status, pageable));
        }
        // Default: return all flagged for review dashboard
        return ResponseEntity.ok(paymentService.getPaymentsByStatus(PaymentStatus.FLAGGED, pageable));
    }

    @GetMapping("/merchant/{merchantId}")
    public ResponseEntity<Page<PaymentDTO.Response>> getByMerchant(
            @PathVariable String merchantId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(paymentService.getPaymentsByMerchant(merchantId, pageable));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<PaymentDTO.Response> approvePayment(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentService.approvePayment(id));
    }
}
