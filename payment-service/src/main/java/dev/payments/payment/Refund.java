package dev.payments.payment;

import java.time.Instant;
import java.util.UUID;

public record Refund(UUID id,UUID paymentId,long amountMinor,String currency,String status,
                     Instant createdAt,Instant completedAt,Instant rejectedAt,String rejectionReason) {}
