package dev.payments.ledger;

import dev.payments.contract.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties="ledger.grpc.port=0")
class RefundLedgerTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
    }
    @Autowired LedgerStore ledger;
    @Autowired AccountStore accounts;
    @Autowired LedgerRefundStore refunds;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    String account() {String id=UUID.randomUUID().toString();accounts.create(id,"USD");return id;}
    FundingResult fund(String id,long amount) {
        return ledger.fund(FundingRequest.newBuilder().setIdempotencyKey(UUID.randomUUID().toString()).setAccountId(id).setAmountMinor(amount).setCurrency("USD").build());
    }
    Transfer transfer(String from,String to,long amount) {
        return Transfer.newBuilder().setPaymentId(UUID.randomUUID().toString()).setSourceAccount(from).setDestinationAccount(to).setAmountMinor(amount).setCurrency("USD").build();
    }
    Transfer payment(long amount) {
        String from=account(),to=account();fund(from,amount);var p=transfer(from,to,amount);
        assertThat(ledger.submit(p).getOutcome()).isEqualTo(TransferOutcome.POSTED);return p;
    }
    RefundTransfer refund(Transfer p,long amount) {return request(p.getPaymentId(),amount);}
    RefundTransfer request(String paymentId,long amount) {
        return RefundTransfer.newBuilder().setRefundId(UUID.randomUUID().toString()).setPaymentId(paymentId).setAmountMinor(amount).setCurrency("USD").build();
    }

    @Test void fullAndPartialRefundsReverseAccountsAndPreserveOriginal() {
        var full=payment(500);var original=ledger.get(full.getPaymentId());var request=refund(full,500);
        assertThat(refunds.submit(request).getOutcome()).isEqualTo(RefundOutcome.REFUND_POSTED);
        assertThat(accounts.get(full.getSourceAccount()).getBalanceMinor()).isEqualTo("500");
        assertThat(accounts.get(full.getDestinationAccount()).getBalanceMinor()).isEqualTo("0");
        assertThat(ledger.get(full.getPaymentId())).isEqualTo(original);
        var reversal=ledger.get(request.getRefundId());
        assertThat(reversal.getTransfer().getSourceAccount()).isEqualTo(full.getDestinationAccount());
        assertThat(reversal.getTransfer().getDestinationAccount()).isEqualTo(full.getSourceAccount());
        assertThat(reversal.getEntriesCount()).isEqualTo(2);
        var partial=payment(1000);
        assertThat(refunds.submit(refund(partial,300)).getOutcome()).isEqualTo(RefundOutcome.REFUND_POSTED);
        assertThat(refunds.submit(refund(partial,700)).getOutcome()).isEqualTo(RefundOutcome.REFUND_POSTED);
        assertThat(refunds.submit(refund(partial,1)).getOutcome()).isEqualTo(RefundOutcome.REFUND_LIMIT_EXCEEDED);
        assertThat(accounts.get(partial.getSourceAccount()).getBalanceMinor()).isEqualTo("1000");
    }

    @Test void concurrentReplayHasOneEffectAndConflictingFieldsAreRejected() throws Exception {
        var p=payment(1000);var request=refund(p,200);
        var results=concurrent(24,i->refunds.submit(request));
        assertThat(results).allSatisfy(r->assertThat(r).isEqualTo(results.getFirst()));
        assertThat(accounts.get(p.getSourceAccount()).getBalanceMinor()).isEqualTo("200");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE payment_id=?::uuid",Long.class,request.getRefundId())).isEqualTo(2);
        assertThatThrownBy(()->refunds.submit(request.toBuilder().setAmountMinor(201).build())).isInstanceOf(LedgerStore.Conflict.class);
        assertThatThrownBy(()->refunds.submit(request.toBuilder().setPaymentId(UUID.randomUUID().toString()).build())).isInstanceOf(LedgerStore.Conflict.class);
    }

    @Test void concurrentDistinctRefundsCannotExceedOriginalEvenWithExtraMerchantFunds() throws Exception {
        var p=payment(1000);fund(p.getDestinationAccount(),2000);
        var results=concurrent(20,i->refunds.submit(refund(p,100)));
        assertThat(results.stream().filter(r->r.getOutcome()==RefundOutcome.REFUND_POSTED).count()).isEqualTo(10);
        assertThat(results.stream().filter(r->r.getOutcome()==RefundOutcome.REFUND_LIMIT_EXCEEDED).count()).isEqualTo(10);
        assertThat(accounts.get(p.getSourceAccount()).getBalanceMinor()).isEqualTo("1000");
        assertThat(accounts.get(p.getDestinationAccount()).getBalanceMinor()).isEqualTo("2000");
        assertThat(jdbc.queryForObject("SELECT sum(amount_minor) FROM refund_decisions WHERE original_payment_id=?::uuid AND outcome='REFUND_POSTED'",java.math.BigDecimal.class,p.getPaymentId())).isEqualByComparingTo("1000");
    }

    @Test void insufficientFundsRejectionRemainsFinalAfterFunding() {
        var p=payment(1000);ledger.submit(transfer(p.getDestinationAccount(),account(),1000));
        var request=refund(p,200);
        assertThat(refunds.submit(request).getOutcome()).isEqualTo(RefundOutcome.REFUND_INSUFFICIENT_FUNDS);
        fund(p.getDestinationAccount(),500);
        assertThat(refunds.submit(request).getOutcome()).isEqualTo(RefundOutcome.REFUND_INSUFFICIENT_FUNDS);
        assertThat(refunds.submit(refund(p,200)).getOutcome()).isEqualTo(RefundOutcome.REFUND_POSTED);
        assertThat(accounts.get(p.getSourceAccount()).getBalanceMinor()).isEqualTo("200");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journals WHERE payment_id=?::uuid",Long.class,request.getRefundId())).isZero();
    }

    @Test void rejectsMissingOriginalFundingAndRefundJournals() {
        var missing=request(UUID.randomUUID().toString(),1);
        assertThat(refunds.submit(missing).getOutcome()).isEqualTo(RefundOutcome.REFUND_PAYMENT_NOT_FOUND);
        String a=account();var funded=fund(a,100);
        assertThat(refunds.submit(request(funded.getFundingId(),100)).getOutcome()).isEqualTo(RefundOutcome.REFUND_PAYMENT_NOT_REFUNDABLE);
        var p=payment(100);var first=refund(p,100);refunds.submit(first);
        assertThat(refunds.submit(request(first.getRefundId(),100)).getOutcome()).isEqualTo(RefundOutcome.REFUND_PAYMENT_NOT_REFUNDABLE);
        for(var invalid:List.of(first.toBuilder().setAmountMinor(0).build(),first.toBuilder().setCurrency("EUR").build(),first.toBuilder().setRefundId("bad").build()))
            assertThatThrownBy(()->refunds.submit(invalid)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectedRefundAndOrdinaryTransferMayReuseAnIdInEitherOrder() {
        var existing=payment(100);
        var request=request(UUID.randomUUID().toString(),1).toBuilder().setRefundId(existing.getPaymentId()).build();
        assertThat(refunds.submit(request).getOutcome()).isEqualTo(RefundOutcome.REFUND_PAYMENT_NOT_FOUND);
        assertThat(refunds.submit(request).getOutcome()).isEqualTo(RefundOutcome.REFUND_PAYMENT_NOT_FOUND);
        assertThat(ledger.get(existing.getPaymentId()).getTransfer()).isEqualTo(existing);
    }

    @Test void secondEntryFailureRollsBackDecisionAndMoneyMovement() {
        var p=payment(1000);var request=refund(p,100);
        jdbc.execute("CREATE FUNCTION fail_refund_credit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.side='CREDIT' THEN RAISE EXCEPTION 'injected refund failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER injected_refund_failure BEFORE INSERT ON entries FOR EACH ROW EXECUTE FUNCTION fail_refund_credit()");
        try {
            assertThatThrownBy(()->refunds.submit(request)).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_decisions WHERE refund_id=?::uuid",Long.class,request.getRefundId())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_decisions WHERE payment_id=?::uuid",Long.class,request.getRefundId())).isZero();
            assertThat(accounts.get(p.getSourceAccount()).getBalanceMinor()).isEqualTo("0");
            assertThat(accounts.get(p.getDestinationAccount()).getBalanceMinor()).isEqualTo("1000");
        } finally {jdbc.execute("DROP TRIGGER injected_refund_failure ON entries");jdbc.execute("DROP FUNCTION fail_refund_credit()");}
        assertThat(refunds.submit(request).getOutcome()).isEqualTo(RefundOutcome.REFUND_POSTED);
    }

    @Test void immutableRefundDecisionsCannotCommitUnfinished() {
        var p=payment(100);var request=refund(p,50);refunds.submit(request);
        assertThatThrownBy(()->jdbc.update("UPDATE refund_decisions SET amount_minor=51 WHERE refund_id=?::uuid",request.getRefundId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM refund_decisions WHERE refund_id=?::uuid",request.getRefundId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->tx.executeWithoutResult(s->jdbc.update("INSERT INTO refund_decisions(refund_id,original_payment_id,amount_minor,currency,outcome) VALUES (?::uuid,?::uuid,1,'USD','PROCESSING')",UUID.randomUUID().toString(),p.getPaymentId()))).isInstanceOf(RuntimeException.class);
    }

    @Test void opposingRefundsAndOrdinaryTransfersShareLockOrder() throws Exception {
        String a=account(),b=account();fund(a,1000);fund(b,1000);
        var ab=transfer(a,b,500);var ba=transfer(b,a,500);ledger.submit(ab);ledger.submit(ba);
        var results=concurrent(24,i->i<20
            ?refunds.submit(refund(i%2==0?ab:ba,50)).getOutcome()==RefundOutcome.REFUND_POSTED
            :ledger.submit(transfer(i%2==0?a:b,i%2==0?b:a,10)).getOutcome()==TransferOutcome.POSTED);
        assertThat(results).containsOnly(true);
        assertThat(accounts.get(a).getBalanceMinor()).isEqualTo("1000");
        assertThat(accounts.get(b).getBalanceMinor()).isEqualTo("1000");
    }

    private <T> List<T> concurrent(int count,java.util.function.IntFunction<T> action) throws Exception {
        try(var pool=Executors.newFixedThreadPool(12)) {
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<T>>();
            for(int i=0;i<count;i++){int index=i;futures.add(pool.submit(()->{start.await();return action.apply(index);}));}
            start.countDown();var results=new ArrayList<T>();for(var f:futures)results.add(f.get(30,TimeUnit.SECONDS));return results;
        }
    }
}
