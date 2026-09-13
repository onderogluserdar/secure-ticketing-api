package com.onderogluserdar.ticketing.audit;

import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class AuditService {

    private final AuditLogRepository auditLogs;

    AuditService(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    @Transactional
    public void record(AuditAction action, UUID actorId, String resourceType, UUID resourceId) {
        write(action, actorId, resourceType, resourceId == null ? null : resourceId.toString());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSecurityEvent(AuditAction action, UUID actorId) {
        write(action, actorId, null, null);
    }

    private void write(AuditAction action, UUID actorId, String resourceType, String resourceId) {
        HttpServletRequest request = currentRequest();
        auditLogs.save(AuditLog.of(
                action,
                actorId,
                resourceType,
                resourceId,
                request == null ? null : request.getRemoteAddr(),
                request == null ? null : request.getHeader(HttpHeaders.USER_AGENT),
                Instant.now()));
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest()
                : null;
    }
}
