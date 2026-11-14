package dev.payments.payment.reconciliation;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static dev.payments.payment.reconciliation.ReconciliationRequest.*;
import static dev.payments.payment.reconciliation.ReconciliationEngine.*;

class ReconciliationEngineTest {
    static final Instant FROM=Instant.parse("2026-01-01T00:00:00Z"),TO=FROM.plusSeconds(86400);
    static final UUID A=UUID.fromString("00000000-0000-0000-0000-000000000001"),B=UUID.fromString("00000000-0000-0000-0000-000000000002"),C=UUID.fromString("00000000-0000-0000-0000-000000000003");
    ProcessorRecord row(String ref,OperationType type,UUID id,long amount,String currency){return new ProcessorRecord(ref,type,id,amount,currency);}
    Internal local(OperationType type,UUID id,String status){return new Internal(type,id,"merchant",FROM,status,2500,"USD");}
    Comparison compare(List<ProcessorRecord> rows,Internal...local){return ReconciliationEngine.compare(new ReconciliationRequest("merchant",FROM,TO,rows),List.of(local));}

    @Test void exactPostedPaymentAndRefundMatchWithoutNettingAmounts() {
        var result=compare(List.of(row("p",OperationType.PAYMENT,A,2500,"USD"),row("r",OperationType.REFUND,B,2500,"USD")),
            local(OperationType.PAYMENT,A,"COMPLETED"),local(OperationType.REFUND,B,"COMPLETED"));
        assertThat(result.internalCount()).isEqualTo(2);assertThat(result.matchedCount()).isEqualTo(2);assertThat(result.findings()).isEmpty();
    }
    @Test void mismatchesRetainExactExpectedActualEvidenceAndDoNotAlsoCountAsMissing() {
        var result=compare(List.of(row("p",OperationType.PAYMENT,A,2499,"EUR")),local(OperationType.PAYMENT,A,"COMPLETED"));
        assertThat(result.matchedCount()).isZero();assertThat(result.findings()).extracting(Finding::code).containsExactly("AMOUNT_MISMATCH","CURRENCY_MISMATCH");
        assertThat(result.findings().getFirst().expected().amountMinor()).isEqualTo(2500);
        assertThat(result.findings().getFirst().actual().amountMinor()).isEqualTo(2499);
        assertThat(result.findings().getFirst().processorRow()).isEqualTo(1);
    }
    @Test void absentPostedOperationsAndUnknownProcessorRowsAreDifferentFindings() {
        var result=compare(List.of(row("unknown",OperationType.PAYMENT,C,2500,"USD")),local(OperationType.REFUND,B,"COMPLETED"),local(OperationType.PAYMENT,A,"COMPLETED"));
        assertThat(result.findings()).extracting(Finding::code).containsExactly("MISSING_INTERNAL","MISSING_PROCESSOR","MISSING_PROCESSOR");
        assertThat(result.findings()).extracting(Finding::operationId).containsExactly(C,A,B);
        assertThat(result.findings()).extracting(Finding::ordinal).containsExactly(1,2,3);
        assertThat(result.findings().get(1).actual()).isNull();
    }
    @Test void duplicateGroupsAreNotCleanMatchesIncludingFirstOccurrences() {
        var result=compare(List.of(row("shared",OperationType.PAYMENT,A,2500,"USD"),row("shared",OperationType.PAYMENT,A,2500,"USD"),
            row("shared",OperationType.PAYMENT,B,2500,"USD")),local(OperationType.PAYMENT,A,"COMPLETED"),local(OperationType.PAYMENT,B,"COMPLETED"));
        assertThat(result.matchedCount()).isZero();
        assertThat(result.findings()).extracting(Finding::code).containsExactly("DUPLICATE_OPERATION","DUPLICATE_REFERENCE","DUPLICATE_REFERENCE");
        assertThat(result.findings()).extracting(Finding::processorRow).containsExactly(2,2,3);
    }
    @Test void pendingAndRejectedAreAmbiguousPostingClaimsNotMissingWhenAbsent() {
        var result=compare(List.of(row("pending",OperationType.PAYMENT,A,2500,"USD"),row("rejected",OperationType.PAYMENT,B,2500,"USD")),
            local(OperationType.PAYMENT,A,"PENDING"),local(OperationType.PAYMENT,B,"REJECTED"),local(OperationType.PAYMENT,C,"PENDING"));
        assertThat(result.internalCount()).isEqualTo(3);assertThat(result.findings()).extracting(Finding::code).containsExactly("INTERNAL_NOT_COMPLETED","INTERNAL_NOT_COMPLETED");
        assertThat(result.findings().getFirst().expected().status()).isEqualTo("PENDING");
    }
    @Test void knownWrongMerchantOrWindowIsOutOfScopeAndUpperBoundIsExclusive() {
        var result=compare(List.of(row("end",OperationType.PAYMENT,A,2500,"USD"),row("other",OperationType.REFUND,B,2500,"USD")),
            new Internal(OperationType.PAYMENT,A,"merchant",TO,"COMPLETED",2500,"USD"),
            new Internal(OperationType.REFUND,B,"other",FROM,"COMPLETED",2500,"USD"),local(OperationType.PAYMENT,C,"COMPLETED"));
        assertThat(result.internalCount()).isEqualTo(1);assertThat(result.findings()).extracting(Finding::code).containsExactly("OUT_OF_SCOPE","OUT_OF_SCOPE","MISSING_PROCESSOR");
    }
}
