package dev.payments.ledger;

import dev.payments.contract.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties="ledger.grpc.port=0")
class AccountLedgerTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);
        r.add("spring.datasource.username",DB::getUsername);
        r.add("spring.datasource.password",DB::getPassword);
    }
    @Autowired AccountStore accounts;
    @Autowired LedgerStore ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    String account() {
        String id=UUID.randomUUID().toString();accounts.create(id,"USD");return id;
    }
    FundingRequest funding(String id,long amount,String key) {
        return FundingRequest.newBuilder().setAccountId(id).setAmountMinor(amount).setCurrency("USD").setIdempotencyKey(key).build();
    }
    void fund(String id,long amount) {assertThat(ledger.fund(funding(id,amount,UUID.randomUUID().toString())).getOutcome()).isEqualTo(TransferOutcome.POSTED);}
    Transfer transfer(String from,String to,long amount) {
        return Transfer.newBuilder().setPaymentId(UUID.randomUUID().toString()).setSourceAccount(from).setDestinationAccount(to).setAmountMinor(amount).setCurrency("USD").build();
    }
    BigInteger balance(String id) {return new BigInteger(accounts.get(id).getBalanceMinor());}

    @Test void createsEmptyAccountsIdempotentlyAndProtectsIssuer() {
        String id=UUID.randomUUID().toString();
        assertThat(accounts.create(id,"USD").getCreated()).isTrue();
        assertThat(accounts.create(id,"USD").getCreated()).isFalse();
        assertThat(balance(id)).isEqualTo(BigInteger.ZERO);
        for(String invalid:List.of(""," ","x".repeat(101),"__simulation_issuer__"))
            assertThatThrownBy(()->accounts.create(invalid,"USD")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->accounts.get("__simulation_issuer__")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->accounts.create("other","EUR")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->accounts.get(UUID.randomUUID().toString())).isInstanceOf(LedgerStore.NotFound.class);
        assertThatThrownBy(()->ledger.submit(transfer(id,"__simulation_issuer__",1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void concurrentFundingReplaysOneBalancedJournalAndRejectsChangedFields() throws Exception {
        String id=account();var request=funding(id,10000,UUID.randomUUID().toString());
        var results=concurrent(24,i->ledger.fund(request));
        assertThat(results.stream().filter(FundingResult::getCreated).count()).isEqualTo(1);
        assertThat(results.stream().map(FundingResult::getFundingId).distinct().count()).isEqualTo(1);
        assertThat(balance(id)).isEqualTo(new BigInteger("10000"));
        String journal=results.getFirst().getFundingId();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE payment_id=?::uuid",Long.class,journal)).isEqualTo(2);
        assertThatThrownBy(()->ledger.fund(request.toBuilder().setAmountMinor(10001).build())).isInstanceOf(LedgerStore.Conflict.class);
        assertThatThrownBy(()->ledger.fund(request.toBuilder().setAccountId(account()).build())).isInstanceOf(LedgerStore.Conflict.class);
    }

    @Test void distinctConcurrentTransfersCannotOverspend() throws Exception {
        String from=account(),to=account();fund(from,5000);
        var results=concurrent(24,i->ledger.submit(transfer(from,to,500)));
        assertThat(results.stream().filter(r->r.getOutcome()==TransferOutcome.POSTED).count()).isEqualTo(10);
        assertThat(results.stream().filter(r->r.getOutcome()==TransferOutcome.INSUFFICIENT_FUNDS).count()).isEqualTo(14);
        assertThat(balance(from)).isEqualTo(BigInteger.ZERO);
        assertThat(balance(to)).isEqualTo(new BigInteger("5000"));
        assertThat(jdbc.queryForObject("SELECT sum(CASE WHEN side='CREDIT' THEN amount_minor::numeric ELSE -amount_minor::numeric END) FROM entries",java.math.BigDecimal.class)).isEqualByComparingTo("0");
        for(var result:results) if(result.getOutcome()==TransferOutcome.INSUFFICIENT_FUNDS)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM journals WHERE payment_id=?::uuid",Long.class,result.getPaymentId())).isZero();
    }

    @Test void opposingTransfersUseConsistentLockOrder() throws Exception {
        String a=account(),b=account();fund(a,10000);fund(b,10000);
        var results=concurrent(40,i->ledger.submit(i%2==0?transfer(a,b,100):transfer(b,a,100)));
        assertThat(results).allSatisfy(r->assertThat(r.getOutcome()).isEqualTo(TransferOutcome.POSTED));
        assertThat(balance(a)).isEqualTo(new BigInteger("10000"));
        assertThat(balance(b)).isEqualTo(new BigInteger("10000"));
    }

    @Test void rejectionRemainsFinalAfterLaterFundingOrAccountCreation() {
        String from=account(),to=account();var t=transfer(from,to,500);
        var rejected=ledger.submit(t);
        assertThat(rejected.getOutcome()).isEqualTo(TransferOutcome.INSUFFICIENT_FUNDS);
        fund(from,1000);
        assertThat(ledger.submit(t)).isEqualTo(rejected);
        assertThat(balance(from)).isEqualTo(new BigInteger("1000"));
        assertThatThrownBy(()->ledger.submit(t.toBuilder().setAmountMinor(501).build())).isInstanceOf(LedgerStore.Conflict.class);
        String missing=UUID.randomUUID().toString();var unknown=transfer(from,missing,500);
        assertThat(ledger.submit(unknown).getOutcome()).isEqualTo(TransferOutcome.ACCOUNT_NOT_FOUND);
        accounts.create(missing,"USD");
        assertThat(ledger.submit(unknown).getOutcome()).isEqualTo(TransferOutcome.ACCOUNT_NOT_FOUND);
        assertThat(ledger.submit(transfer(from,to,500)).getOutcome()).isEqualTo(TransferOutcome.POSTED);
        assertThat(balance(from)).isEqualTo(new BigInteger("500"));
    }

    @Test void fundingAndSpendingRaceHasOneSerializableOutcome() throws Exception {
        String from=account(),to=account();var t=transfer(from,to,100);
        var outcomes=concurrent(2,i->i==0?ledger.fund(funding(from,100,UUID.randomUUID().toString())).getOutcome():ledger.submit(t).getOutcome());
        assertThat(outcomes.getFirst()).isEqualTo(TransferOutcome.POSTED);
        var outcome=outcomes.get(1);
        assertThat(outcome).isIn(TransferOutcome.POSTED,TransferOutcome.INSUFFICIENT_FUNDS);
        assertThat(balance(from)).isEqualTo(outcome==TransferOutcome.POSTED?BigInteger.ZERO:BigInteger.valueOf(100));
        assertThat(balance(to)).isEqualTo(outcome==TransferOutcome.POSTED?BigInteger.valueOf(100):BigInteger.ZERO);
        assertThat(ledger.submit(t).getOutcome()).isEqualTo(outcome);
    }

    @Test void missingAccountFundingRejectionIsIdempotentAndDecisionsAreImmutable() {
        String id=UUID.randomUUID().toString();var request=funding(id,100,UUID.randomUUID().toString());
        var result=ledger.fund(request);
        assertThat(result.getOutcome()).isEqualTo(TransferOutcome.ACCOUNT_NOT_FOUND);
        accounts.create(id,"USD");
        assertThat(ledger.fund(request).getOutcome()).isEqualTo(TransferOutcome.ACCOUNT_NOT_FOUND);
        assertThat(balance(id)).isEqualTo(BigInteger.ZERO);
        assertThatThrownBy(()->jdbc.update("UPDATE transfer_decisions SET outcome='POSTED' WHERE payment_id=?::uuid",result.getFundingId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM transfer_decisions WHERE payment_id=?::uuid",result.getFundingId())).isInstanceOf(RuntimeException.class);
        String other=account();var t=transfer(id,other,1);
        assertThatThrownBy(()->tx.executeWithoutResult(s->jdbc.update("""
            INSERT INTO transfer_decisions(payment_id,kind,source_account,destination_account,amount_minor,currency,outcome)
            VALUES (?::uuid,'TRANSFER',?,?,1,'USD','PROCESSING')
            """,t.getPaymentId(),id,other))).isInstanceOf(RuntimeException.class);
    }

    private <T> List<T> concurrent(int count,java.util.function.IntFunction<T> action) throws Exception {
        try(var pool=Executors.newFixedThreadPool(Math.min(count,12))) {
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<T>>();
            for(int i=0;i<count;i++) {int index=i;futures.add(pool.submit(()->{start.await();return action.apply(index);}));}
            start.countDown();var results=new ArrayList<T>();
            for(var f:futures) results.add(f.get(30,TimeUnit.SECONDS));return results;
        }
    }
}
