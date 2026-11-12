package dev.payments.ledger;

import dev.payments.contract.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class AccountMigrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");

    @Test void upgradePreservesLegacyDebtJournalAndIdempotency() {
        String schema="upgrade_"+UUID.randomUUID().toString().replace("-","");
        String url=DB.getJdbcUrl();
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,DB.getUsername(),DB.getPassword());
        Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/ledger").target("1").load().migrate();
        var jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var old=Transfer.newBuilder().setPaymentId(UUID.randomUUID().toString()).setSourceAccount("legacy-alice")
                .setDestinationAccount("legacy-merchant").setAmountMinor(2500).setCurrency("USD").build();
        tx.executeWithoutResult(s->{
            jdbc.update("INSERT INTO journals(payment_id,source_account,destination_account,amount_minor,currency) VALUES (?::uuid,'legacy-alice','legacy-merchant',2500,'USD')",old.getPaymentId());
            jdbc.update("INSERT INTO entries VALUES (?::uuid,'DEBIT','legacy-alice',2500,'USD')",old.getPaymentId());
            jdbc.update("INSERT INTO entries VALUES (?::uuid,'CREDIT','legacy-merchant',2500,'USD')",old.getPaymentId());
        });
        Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/ledger").load().migrate();
        var accounts=new AccountStore(jdbc);var ledger=new LedgerStore(jdbc,tx);
        assertThat(accounts.get("legacy-alice").getBalanceMinor()).isEqualTo("-2500");
        assertThat(accounts.get("legacy-merchant").getBalanceMinor()).isEqualTo("2500");
        assertThat(ledger.post(old).getEntriesCount()).isEqualTo(2);
        var next=old.toBuilder().setPaymentId(UUID.randomUUID().toString()).build();
        assertThat(ledger.submit(next).getOutcome()).isEqualTo(TransferOutcome.INSUFFICIENT_FUNDS);
        ledger.fund(FundingRequest.newBuilder().setIdempotencyKey("upgrade-funding").setAccountId("legacy-alice").setAmountMinor(5000).setCurrency("USD").build());
        assertThat(accounts.get("legacy-alice").getBalanceMinor()).isEqualTo("2500");
        assertThat(ledger.submit(next).getOutcome()).isEqualTo(TransferOutcome.INSUFFICIENT_FUNDS);
        assertThat(ledger.submit(next.toBuilder().setPaymentId(UUID.randomUUID().toString()).build()).getOutcome()).isEqualTo(TransferOutcome.POSTED);
        assertThat(accounts.get("legacy-alice").getBalanceMinor()).isEqualTo("0");
    }

    @Test void upgradeFromV2PreservesFundedPaymentAndEnablesRefund() {
        String schema="v2_"+UUID.randomUUID().toString().replace("-","");String url=DB.getJdbcUrl();
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,DB.getUsername(),DB.getPassword());
        Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/ledger").target("2").load().migrate();
        var jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var accounts=new AccountStore(jdbc);var ledger=new LedgerStore(jdbc,tx);
        accounts.create("payer","USD");accounts.create("merchant","USD");
        ledger.fund(FundingRequest.newBuilder().setIdempotencyKey("v2-funding").setAccountId("payer").setAmountMinor(1000).setCurrency("USD").build());
        var payment=Transfer.newBuilder().setPaymentId(UUID.randomUUID().toString()).setSourceAccount("payer").setDestinationAccount("merchant").setAmountMinor(1000).setCurrency("USD").build();
        var original=ledger.post(payment);
        Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/ledger").load().migrate();
        var refunds=new LedgerRefundStore(jdbc,tx,ledger);
        var request=RefundTransfer.newBuilder().setRefundId(UUID.randomUUID().toString()).setPaymentId(payment.getPaymentId()).setAmountMinor(300).setCurrency("USD").build();
        assertThat(refunds.submit(request).getOutcome()).isEqualTo(RefundOutcome.REFUND_POSTED);
        assertThat(ledger.post(payment)).isEqualTo(original);
        assertThat(accounts.get("payer").getBalanceMinor()).isEqualTo("300");
        assertThat(accounts.get("merchant").getBalanceMinor()).isEqualTo("700");
    }
}
