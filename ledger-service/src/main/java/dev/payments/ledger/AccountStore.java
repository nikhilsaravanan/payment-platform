package dev.payments.ledger;

import dev.payments.contract.Account;
import dev.payments.contract.AccountResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigInteger;

@Repository
public class AccountStore {
    static final String ISSUER = "__simulation_issuer__";
    private final JdbcTemplate jdbc;

    public AccountStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public AccountResult create(String id, String currency) {
        validateId(id);
        if (!"USD".equals(currency)) throw new IllegalArgumentException("Unsupported currency");
        int inserted = jdbc.update("""
            INSERT INTO accounts(account_id,currency,kind) VALUES (?,?,'CUSTOMER')
            ON CONFLICT (account_id) DO NOTHING
            """, id, currency);
        return AccountResult.newBuilder().setAccount(get(id)).setCreated(inserted == 1).build();
    }

    public Account get(String id) {
        validateId(id);
        var rows = jdbc.query("""
            SELECT a.account_id,a.currency,COALESCE((SELECT sum(CASE side WHEN 'CREDIT'
                THEN amount_minor::numeric ELSE -amount_minor::numeric END) FROM entries WHERE account=a.account_id),0) AS balance
            FROM accounts a WHERE account_id=? AND kind='CUSTOMER'
            """, (rs, n) -> Account.newBuilder().setAccountId(rs.getString("account_id"))
                .setCurrency(rs.getString("currency")).setBalanceMinor(rs.getBigDecimal("balance").toBigIntegerExact().toString()).build(), id);
        if (rows.isEmpty()) throw new LedgerStore.NotFound();
        return rows.getFirst();
    }

    static BigInteger balance(JdbcTemplate jdbc, String id) {
        return jdbc.queryForObject("""
            SELECT COALESCE(sum(CASE side WHEN 'CREDIT' THEN amount_minor::numeric ELSE -amount_minor::numeric END),0)
            FROM entries WHERE account=?
            """, java.math.BigDecimal.class, id).toBigIntegerExact();
    }

    static void validateId(String id) {
        if (id == null || id.isBlank() || id.length() > 100 || ISSUER.equals(id))
            throw new IllegalArgumentException("Invalid customer account");
    }
}
