package dev.payments.ledger;

import dev.payments.contract.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.io.*;
import java.time.Instant;
import java.util.*;

@Repository
public class LedgerHistoryStore {
    private final JdbcTemplate jdbc;
    public LedgerHistoryStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private record Cursor(Instant createdAt,UUID id) {}

    public HistoryPage list(HistoryRequest request) {
        String account=request.getAccountId();AccountStore.validateId(account);
        int size=request.getPageSize();if(size<1 || size>100)throw new IllegalArgumentException("Page size must be 1..100");
        Cursor cursor=request.getCursor().isEmpty()?null:decode(request.getCursor(),account);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM accounts WHERE account_id=? AND kind='CUSTOMER')",Boolean.class,account)))
            throw new LedgerStore.NotFound();
        String boundary=cursor==null?"":" AND (j.created_at,j.payment_id)<(?::timestamptz,?::uuid)";
        var args=new ArrayList<Object>();args.add(account);
        if(cursor!=null){args.add(cursor.createdAt().toString());args.add(cursor.id().toString());}args.add(size+1);
        var rows=jdbc.query("""
            SELECT j.payment_id,j.created_at,j.source_account,j.destination_account,j.amount_minor,j.currency,
                d.kind,e.side,r.original_payment_id
            FROM entries e JOIN journals j ON j.payment_id=e.payment_id
            JOIN transfer_decisions d ON d.payment_id=j.payment_id
            LEFT JOIN refund_decisions r ON r.refund_id=j.payment_id AND d.kind='REFUND'
            WHERE e.account=?
            """+boundary+" ORDER BY j.created_at DESC,j.payment_id DESC LIMIT ?",(rs,n)->{
                String kind=rs.getString("kind"),original=rs.getString("original_payment_id");
                return LedgerTransaction.newBuilder().setTransactionId(rs.getString("payment_id"))
                    .setType(kind.equals("TRANSFER")?"PAYMENT":kind).setAccountId(account)
                    .setCounterparty(account.equals(rs.getString("source_account"))?rs.getString("destination_account"):rs.getString("source_account"))
                    .setDirection(rs.getString("side")).setAmountMinor(rs.getLong("amount_minor")).setCurrency(rs.getString("currency"))
                    .setCreatedAt(rs.getTimestamp("created_at").toInstant().toString()).setOriginalPaymentId(original==null?"":original).build();
            },args.toArray());
        var visible=rows.subList(0,Math.min(rows.size(),size));
        var page=HistoryPage.newBuilder().addAllItems(visible);
        if(rows.size()>size){var last=visible.getLast();page.setNextCursor(encode(account,Instant.parse(last.getCreatedAt()),UUID.fromString(last.getTransactionId())));}
        return page.build();
    }

    private static String encode(String account,Instant createdAt,UUID id) {
        try {
            var bytes=new ByteArrayOutputStream();
            try(var out=new DataOutputStream(bytes)) {
                out.writeByte(1);out.writeUTF(account);out.writeLong(createdAt.getEpochSecond());out.writeInt(createdAt.getNano());out.writeUTF(id.toString());
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch(IOException e){throw new IllegalStateException("Cannot encode history cursor",e);}
    }
    private static Cursor decode(String token,String account) {
        if(token.length()>1024)throw new IllegalArgumentException("Invalid history cursor");
        try(var input=new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(token)))) {
            if(input.readUnsignedByte()!=1 || !input.readUTF().equals(account))throw new IllegalArgumentException("Invalid history cursor");
            long seconds=input.readLong();int nanos=input.readInt();
            // Restrict to ISO civil years and PostgreSQL's microsecond precision before JDBC conversion.
            if(seconds< -62135596800L || seconds>253402300799L || nanos<0 || nanos>999999999 || nanos%1000!=0)
                throw new IllegalArgumentException("Invalid history cursor time");
            var cursor=new Cursor(Instant.ofEpochSecond(seconds,nanos),LedgerStore.parseId(input.readUTF()));
            if(input.available()!=0)throw new IllegalArgumentException("Invalid history cursor");return cursor;
        } catch(IOException | IllegalArgumentException e){throw new IllegalArgumentException("Invalid history cursor",e);}
    }
}
