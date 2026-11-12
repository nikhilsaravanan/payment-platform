package dev.payments.ledger;

import dev.payments.contract.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.io.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties="ledger.grpc.port=0")
class HistoryLedgerTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
    }
    @Autowired LedgerHistoryStore history;
    @Autowired LedgerStore ledger;
    @Autowired AccountStore accounts;
    @Autowired LedgerRefundStore refunds;
    @Autowired TransactionTemplate tx;
    String account(){String id=UUID.randomUUID().toString();accounts.create(id,"USD");return id;}
    void fund(String id,long amount){ledger.fund(FundingRequest.newBuilder().setIdempotencyKey(UUID.randomUUID().toString()).setAccountId(id).setAmountMinor(amount).setCurrency("USD").build());}
    Transfer payment(String a,String b,long amount){var t=Transfer.newBuilder().setPaymentId(UUID.randomUUID().toString()).setSourceAccount(a).setDestinationAccount(b).setAmountMinor(amount).setCurrency("USD").build();ledger.submit(t);return t;}
    HistoryPage page(String id,int size,String cursor){return history.list(HistoryRequest.newBuilder().setAccountId(id).setPageSize(size).setCursor(cursor).build());}

    @Test void postedHistoryIncludesFundingPaymentsAndLinkedRefundsOnly() {
        String a=account(),b=account();fund(a,1000);var p=payment(a,b,1000);
        var refund=RefundTransfer.newBuilder().setRefundId(UUID.randomUUID().toString()).setPaymentId(p.getPaymentId()).setAmountMinor(250).setCurrency("USD").build();
        refunds.submit(refund);refunds.submit(refund.toBuilder().setRefundId(UUID.randomUUID().toString()).setAmountMinor(1000).build());
        var records=page(a,20,"").getItemsList();assertThat(records).hasSize(3);
        assertThat(records.stream().map(LedgerTransaction::getType).toList()).containsExactly("REFUND","PAYMENT","FUNDING");
        assertThat(records.getFirst().getOriginalPaymentId()).isEqualTo(p.getPaymentId());
        assertThat(records.getFirst().getDirection()).isEqualTo("CREDIT");assertThat(records.getFirst().getAmountMinor()).isEqualTo(250);
        assertThat(records.getFirst().getCounterparty()).isEqualTo(b);
        assertThat(records.get(1).getDirection()).isEqualTo("DEBIT");assertThat(records.get(1).getOriginalPaymentId()).isEmpty();
        assertThat(page(b,20,"").getItemsCount()).isEqualTo(2);
    }

    @Test void keysetPaginationHandlesTiedTimestampsAndNewerCommitsWithoutDuplicates() {
        String a=account(),b=account();fund(a,1000);var expected=new ArrayList<String>();
        tx.executeWithoutResult(s->{for(int i=0;i<7;i++)expected.add(payment(a,b,10).getPaymentId());});
        expected.sort(Comparator.reverseOrder());
        var first=page(b,2,"");var actual=new ArrayList<>(first.getItemsList().stream().map(LedgerTransaction::getTransactionId).toList());
        assertThat(first.getItems(0).getCreatedAt()).isEqualTo(first.getItems(1).getCreatedAt());
        var newer=payment(a,b,1);String cursor=first.getNextCursor();
        while(!cursor.isEmpty()) {
            var next=page(b,2,cursor);assertThat(next.getItemsCount()).isBetween(1,2);
            actual.addAll(next.getItemsList().stream().map(LedgerTransaction::getTransactionId).toList());cursor=next.getNextCursor();
        }
        assertThat(actual).containsExactlyElementsOf(expected);assertThat(new HashSet<>(actual)).hasSize(7);
        assertThat(page(b,2,"").getItems(0).getTransactionId()).isEqualTo(newer.getPaymentId());
    }

    @Test void emptyMissingAndReservedAccountsAndPageBounds() {
        String a=account();assertThat(page(a,20,"").getItemsCount()).isZero();assertThat(page(a,20,"").getNextCursor()).isEmpty();
        for(int size:List.of(-1,0,101,Integer.MAX_VALUE))assertThatThrownBy(()->page(a,size,"")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->page(UUID.randomUUID().toString(),20,"")).isInstanceOf(LedgerStore.NotFound.class);
        assertThatThrownBy(()->page("__simulation_issuer__",20,"")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void nonRefundMovementDoesNotAcquireAnUnrelatedRejectedRefundLink() {
        String a=account(),b=account();fund(a,100);
        String shared=UUID.randomUUID().toString();
        refunds.submit(RefundTransfer.newBuilder().setRefundId(shared).setPaymentId(UUID.randomUUID().toString()).setAmountMinor(1).setCurrency("USD").build());
        ledger.submit(Transfer.newBuilder().setPaymentId(shared).setSourceAccount(a).setDestinationAccount(b).setAmountMinor(10).setCurrency("USD").build());
        var movement=page(b,20,"").getItems(0);
        assertThat(movement.getType()).isEqualTo("PAYMENT");
        assertThat(movement.getOriginalPaymentId()).isEmpty();
    }

    @Test void invalidAndCrossAccountCursorsReturnValidationErrors() throws Exception {
        String a=account(),b=account();fund(a,100);payment(a,b,10);payment(a,b,10);
        String valid=page(b,1,"").getNextCursor();assertThat(valid).isNotEmpty();
        assertThatThrownBy(()->page(a,1,valid)).isInstanceOf(IllegalArgumentException.class);
        for(String token:List.of("%","garbage","x".repeat(1025),cursor(b,Long.MAX_VALUE,0,UUID.randomUUID().toString(),false),
            cursor(b,1000,-1,UUID.randomUUID().toString(),false),cursor(b,1000,1001,UUID.randomUUID().toString(),false),
            cursor(b,1000,0,"bad-id",false),cursor(b,1000,0,UUID.randomUUID().toString(),true)))
            assertThatThrownBy(()->page(b,1,token)).as(token).isInstanceOf(IllegalArgumentException.class);
    }
    String cursor(String account,long seconds,int nanos,String id,boolean trailing) throws Exception {
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeByte(1);out.writeUTF(account);out.writeLong(seconds);out.writeInt(nanos);out.writeUTF(id);if(trailing)out.writeByte(0);}
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    }
}
