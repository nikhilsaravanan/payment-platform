package dev.payments.payment.reconciliation;

import java.time.Instant;
import java.util.*;
import static dev.payments.payment.reconciliation.ReconciliationRequest.*;

/** Pure comparison of one frozen local snapshot with ordered simulated postings. */
public final class ReconciliationEngine {
    private ReconciliationEngine() {}
    public record Internal(OperationType operationType,UUID operationId,String accountId,Instant createdAt,
                           String status,long amountMinor,String currency) {}
    public record Finding(int ordinal,String code,OperationType operationType,UUID operationId,Integer processorRow,
                          Internal expected,ProcessorRecord actual) {}
    public record Comparison(int internalCount,int matchedCount,List<Finding> findings) {}
    private record Key(OperationType type,UUID id) {}
    static boolean inScope(ReconciliationRequest request,Internal row) {
        return row.accountId().equals(request.accountId()) && !row.createdAt().isBefore(request.from()) && row.createdAt().isBefore(request.to());
    }
    public static Comparison compare(ReconciliationRequest request,List<Internal> snapshot) {
        var local=new HashMap<Key,Internal>();var operations=new HashMap<Key,Integer>();var references=new HashMap<String,Integer>();
        for(var row:snapshot)local.put(new Key(row.operationType(),row.operationId()),row);
        for(var row:request.records()) {
            operations.merge(new Key(row.operationType(),row.operationId()),1,Integer::sum);
            references.merge(row.reference(),1,Integer::sum);
        }
        var findings=new ArrayList<Finding>();var seenOperations=new HashSet<Key>();var seenReferences=new HashSet<String>();int matched=0;
        for(int index=0;index<request.records().size();index++) {
            var row=request.records().get(index);var key=new Key(row.operationType(),row.operationId());var expected=local.get(key);
            var codes=new ArrayList<String>();
            if(!seenOperations.add(key))codes.add("DUPLICATE_OPERATION");
            if(!seenReferences.add(row.reference()))codes.add("DUPLICATE_REFERENCE");
            if(expected==null)codes.add("MISSING_INTERNAL");
            else if(!inScope(request,expected))codes.add("OUT_OF_SCOPE");
            else {
                if(!expected.status().equals("COMPLETED"))codes.add("INTERNAL_NOT_COMPLETED");
                if(expected.amountMinor()!=row.amountMinor())codes.add("AMOUNT_MISMATCH");
                if(!expected.currency().equals(row.currency()))codes.add("CURRENCY_MISMATCH");
                if(codes.isEmpty() && operations.get(key)==1 && references.get(row.reference())==1)matched++;
            }
            for(var code:codes)findings.add(new Finding(findings.size()+1,code,row.operationType(),row.operationId(),index+1,expected,row));
        }
        var scoped=snapshot.stream().filter(row->inScope(request,row))
            .sorted(Comparator.comparing((Internal row)->row.operationType().name()).thenComparing(row->row.operationId().toString())).toList();
        for(var row:scoped)if(row.status().equals("COMPLETED") && !operations.containsKey(new Key(row.operationType(),row.operationId())))
            findings.add(new Finding(findings.size()+1,"MISSING_PROCESSOR",row.operationType(),row.operationId(),null,row,null));
        return new Comparison(scoped.size(),matched,List.copyOf(findings));
    }
}
