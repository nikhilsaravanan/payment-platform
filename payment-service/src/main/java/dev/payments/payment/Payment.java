package dev.payments.payment;

import java.time.Instant;
import java.util.UUID;

public record Payment(UUID id,String sourceAccount,String destinationAccount,long amountMinor,
                      String currency,String status,Instant createdAt,Instant completedAt) {}
