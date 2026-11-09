package dev.payments.payment;

import dev.payments.contract.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
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
    public void post(Payment p) {
        stub.withDeadlineAfter(deadlineMillis,TimeUnit.MILLISECONDS).postTransfer(Transfer.newBuilder()
                .setPaymentId(p.id().toString()).setSourceAccount(p.sourceAccount()).setDestinationAccount(p.destinationAccount())
                .setAmountMinor(p.amountMinor()).setCurrency(p.currency()).build());
    }
    @Override @PreDestroy public void close() {
        channel.shutdown();
        try { if(!channel.awaitTermination(3,TimeUnit.SECONDS)) channel.shutdownNow(); }
        catch(InterruptedException e) {channel.shutdownNow();Thread.currentThread().interrupt();}
    }
}
