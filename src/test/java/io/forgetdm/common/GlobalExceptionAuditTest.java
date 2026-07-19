package io.forgetdm.common;

import io.forgetdm.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.ConnectException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionAuditTest {
    @Test
    void validationFailureIsAuditedWithoutDatabaseMessage(CapturedOutput output) {
        AuditService audit = mock(AuditService.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(audit);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/policies");
        String secretSql = "insert into policy(password) values ('secret')";

        handler.integrity(new DataIntegrityViolationException(secretSql), request);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).record(isNull(), eq("VALIDATION_FAILED"), eq("SECURITY"), eq("api-request"),
                isNull(), eq("/api/policies"), eq("FAILURE"), detail.capture(), isNull());
        assertFalse(detail.getValue().contains("secret"));
        assertFalse(detail.getValue().contains("insert"));
        assertFalse(output.getAll().contains("secret"));
        assertFalse(output.getAll().contains("insert into"));
    }

    @Test
    void dependencyFailureIsClassifiedAndSanitized(CapturedOutput output) {
        AuditService audit = mock(AuditService.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(audit);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/datasources/1/test");

        handler.any(new RuntimeException("jdbc:postgresql://user:password@host/db", new ConnectException("refused")), request);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).record(isNull(), eq("DEPENDENCY_FAILED"), eq("SECURITY"), eq("api-request"),
                isNull(), eq("/api/datasources/1/test"), eq("FAILURE"), detail.capture(), isNull());
        assertFalse(detail.getValue().contains("password"));
        assertFalse(detail.getValue().contains("jdbc:"));
        assertFalse(output.getAll().contains("password@"));
        assertFalse(output.getAll().contains("jdbc:"));
    }
}
