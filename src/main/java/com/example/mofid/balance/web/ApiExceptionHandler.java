package com.example.mofid.balance.web;

import com.example.mofid.balance.exception.AccountAlreadyExistsException;
import com.example.mofid.balance.exception.AccountNotFoundException;
import com.example.mofid.balance.exception.InsufficientFundsException;
import com.example.mofid.balance.exception.InvalidRequestException;
import com.example.mofid.balance.exception.TransactionIdConflictException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Maps domain failures to RFC 9457 problem details. */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail invalidRequest(InvalidRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail accountNotFound(AccountNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Account not found", e);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail insufficientFunds(InsufficientFundsException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient funds", e);
    }

    @ExceptionHandler({TransactionIdConflictException.class, AccountAlreadyExistsException.class})
    ProblemDetail conflict(RuntimeException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e);
    }

    /** Lock wait timed out or the database picked this transaction as a deadlock victim. Safe to retry. */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ProblemDetail lockFailure(PessimisticLockingFailureException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The account is busy, retry with the same transactionId");
        problem.setTitle("Temporarily unavailable");
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String title, RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle(title);
        return problem;
    }
}
