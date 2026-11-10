package dev.payments.ledger;

import dev.payments.contract.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.function.Supplier;

@Component
public class LedgerRpc extends LedgerGrpc.LedgerImplBase {
    private static final Logger log=LoggerFactory.getLogger(LedgerRpc.class);
    private final LedgerStore store;
    private final AccountStore accounts;
    public LedgerRpc(LedgerStore store,AccountStore accounts) { this.store=store;this.accounts=accounts; }
    @Override public void postTransfer(Transfer request,StreamObserver<Journal> response) {
        respond(()->store.post(request),response);
    }
    @Override public void getTransfer(TransferId request,StreamObserver<Journal> response) {
        respond(()->store.get(request.getPaymentId()),response);
    }
    @Override public void submitTransfer(Transfer request,StreamObserver<TransferResult> response) {
        respond(()->store.submit(request),response);
    }
    @Override public void createAccount(AccountRequest request,StreamObserver<AccountResult> response) {
        respond(()->accounts.create(request.getAccountId(),request.getCurrency()),response);
    }
    @Override public void getAccount(AccountId request,StreamObserver<Account> response) {
        respond(()->accounts.get(request.getAccountId()),response);
    }
    @Override public void fundAccount(FundingRequest request,StreamObserver<FundingResult> response) {
        respond(()->store.fund(request),response);
    }
    private <T> void respond(Supplier<T> operation,StreamObserver<T> response) {
        try { response.onNext(operation.get());response.onCompleted(); }
        catch(IllegalArgumentException e) { response.onError(Status.INVALID_ARGUMENT.withDescription("Invalid transfer").asRuntimeException()); }
        catch(LedgerStore.Conflict e) { response.onError(Status.ALREADY_EXISTS.withDescription("Payment ID already has another transfer").asRuntimeException()); }
        catch(LedgerStore.NotFound e) { response.onError(Status.NOT_FOUND.withDescription("Transfer not found").asRuntimeException()); }
        catch(LedgerStore.Rejected e) { response.onError(Status.FAILED_PRECONDITION.withDescription(e.outcome().name()).asRuntimeException()); }
        catch(RuntimeException e) {
            log.error("Ledger operation failed",e);
            response.onError(Status.INTERNAL.withDescription("Ledger operation failed").asRuntimeException());
        }
    }
}
