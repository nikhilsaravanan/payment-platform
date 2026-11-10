package dev.payments.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.dao.DataAccessException;
import io.grpc.StatusRuntimeException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log=LoggerFactory.getLogger(ApiErrors.class);
    record Error(String code,String message) {}
    @ExceptionHandler(PaymentStore.Conflict.class)
    ResponseEntity<Error> conflict() { return error(409,"IDEMPOTENCY_CONFLICT","Key already used with different payment fields"); }
    @ExceptionHandler(PaymentStore.NotFound.class)
    ResponseEntity<Error> notFound() { return error(404,"PAYMENT_NOT_FOUND","Payment not found"); }
    @ExceptionHandler({MethodArgumentNotValidException.class,MissingRequestHeaderException.class,
            HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,IllegalArgumentException.class})
    ResponseEntity<Error> badRequest() { return error(400,"INVALID_REQUEST","Check payment fields, UUID and Idempotency-Key"); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Error> unavailable(DataAccessException e) {
        log.error("Payment database operation failed",e);
        return error(503,"SERVICE_UNAVAILABLE","Payment storage is temporarily unavailable");
    }
    @ExceptionHandler(StatusRuntimeException.class)
    ResponseEntity<Error> ledgerError(StatusRuntimeException e) {
        return switch(e.getStatus().getCode()) {
            case INVALID_ARGUMENT -> error(400,"INVALID_REQUEST","Check account and funding fields");
            case ALREADY_EXISTS -> error(409,"IDEMPOTENCY_CONFLICT","Key already used with different funding fields");
            case NOT_FOUND -> error(404,"ACCOUNT_NOT_FOUND","Account not found");
            default -> error(503,"LEDGER_UNAVAILABLE","Ledger is temporarily unavailable; retry with the same key");
        };
    }
    private ResponseEntity<Error> error(int status,String code,String message) {
        return ResponseEntity.status(status).body(new Error(code,message));
    }
}
