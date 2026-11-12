package dev.payments.payment;

import dev.payments.contract.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
public class LedgerClient implements AutoCloseable {
    private final ManagedChannel channel;
    private final LedgerGrpc.LedgerBlockingStub stub;
    private final long deadlineMillis;

    public LedgerClient(@Value("${ledger.host:localhost}") String host,
                        @Value("${ledger.port:9090}") int port,
                        @Value("${ledger.deadline-millis:2000}") long deadlineMillis) {
        if(deadlineMillis<1) throw new IllegalArgumentException("Ledger deadline must be positive");
        channel=ManagedChannelBuilder.forAddress(host,port).usePlaintext().build();
        stub=LedgerGrpc.newBlockingStub(channel);this.deadlineMillis=deadlineMillis;
    }
    public TransferOutcome post(Payment p) {
        var result=stub.withDeadlineAfter(deadlineMillis,TimeUnit.MILLISECONDS).submitTransfer(Transfer.newBuilder()
                .setPaymentId(p.id().toString()).setSourceAccount(p.sourceAccount()).setDestinationAccount(p.destinationAccount())
                .setAmountMinor(p.amountMinor()).setCurrency(p.currency()).build());
        if(!result.getPaymentId().equals(p.id().toString()) ||
                (result.getOutcome()!=TransferOutcome.POSTED && result.getOutcome()!=TransferOutcome.INSUFFICIENT_FUNDS
                        && result.getOutcome()!=TransferOutcome.ACCOUNT_NOT_FOUND))
            throw Status.DATA_LOSS.withDescription("Invalid ledger outcome").asRuntimeException();
        return result.getOutcome();
    }
    public AccountResult createAccount(String id,String currency) {
        return stub.withDeadlineAfter(deadlineMillis,TimeUnit.MILLISECONDS)
                .createAccount(AccountRequest.newBuilder().setAccountId(id).setCurrency(currency).build());
    }
    public RefundOutcome refund(Refund refund) {
        var result=stub.withDeadlineAfter(deadlineMillis,TimeUnit.MILLISECONDS).submitRefund(RefundTransfer.newBuilder()
            .setRefundId(refund.id().toString()).setPaymentId(refund.paymentId().toString()).setAmountMinor(refund.amountMinor()).setCurrency(refund.currency()).build());
        if(!result.getRefundId().equals(refund.id().toString()) || !result.getPaymentId().equals(refund.paymentId().toString())
                || result.getAmountMinor()!=refund.amountMinor() || !result.getCurrency().equals(refund.currency()))
            throw Status.DATA_LOSS.withDescription("Mismatched refund outcome").asRuntimeException();
        return switch(result.getOutcome()) {
            case REFUND_POSTED,REFUND_INSUFFICIENT_FUNDS,REFUND_PAYMENT_NOT_FOUND,REFUND_PAYMENT_NOT_REFUNDABLE,REFUND_LIMIT_EXCEEDED -> result.getOutcome();
            default -> throw Status.DATA_LOSS.withDescription("Unknown refund outcome").asRuntimeException();
        };
    }
    public Account getAccount(String id) {
        return stub.withDeadlineAfter(deadlineMillis,TimeUnit.MILLISECONDS)
                .getAccount(AccountId.newBuilder().setAccountId(id).build());
    }
    public FundingResult fund(String key,String id,long amount,String currency) {
        return stub.withDeadlineAfter(deadlineMillis,TimeUnit.MILLISECONDS).fundAccount(FundingRequest.newBuilder()
                .setIdempotencyKey(key).setAccountId(id).setAmountMinor(amount).setCurrency(currency).build());
    }
    @Override @PreDestroy public void close() {
        channel.shutdown();
        try { if(!channel.awaitTermination(3,TimeUnit.SECONDS)) channel.shutdownNow(); }
        catch(InterruptedException e) {channel.shutdownNow();Thread.currentThread().interrupt();}
    }
}
