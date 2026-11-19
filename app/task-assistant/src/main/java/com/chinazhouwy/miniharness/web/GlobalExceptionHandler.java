package com.chinazhouwy.miniharness.web;

import com.chinazhouwy.miniharness.runtime.HarnessEngine;
import com.chinazhouwy.miniharness.runtime.core.HarnessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 统一错误响应：结构化 errorCode 直接透传给前端与 E2E 断言。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HarnessException.class)
    public ResponseEntity<Map<String, Object>> handleHarness(HarnessException e) {
        HttpStatus status = switch (e.errorCode()) {
            case "TASK_NOT_FOUND", "RUN_NOT_FOUND", "WORKFLOW_NOT_FOUND", "CANDIDATE_NOT_FOUND",
                 "WORKFLOW_NOT_PUBLISHED" -> HttpStatus.NOT_FOUND;
            case "APPROVAL_REJECTED", "CANDIDATE_STATE", "ROLLBACK_TARGET_INVALID",
                 "WORKFLOW_VERSION_CONFLICT", "WORKFLOW_NOT_MATCHED" -> HttpStatus.CONFLICT;
            case "MODEL_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of(
                "errorCode", e.errorCode(),
                "message", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "errorCode", "ILLEGAL_STATE",
                "message", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "errorCode", "INVALID_ARGUMENT",
                "message", String.valueOf(e.getMessage())));
    }
}
