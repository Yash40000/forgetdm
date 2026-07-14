package io.forgetdm.audit;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_events")
public class AuditEventEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

    /** Monotonic chain sequence (assigned by AuditService); the hash chain is ordered by this. */
    @Column(name = "seq") private Long seq;

    @Column(nullable = false) private String actor;
    @Column(nullable = false) private String action;
    private String category;

    @Column(name = "resource_type") private String resourceType;
    @Column(name = "resource_id") private String resourceId;
    @Column(name = "resource_name") private String resourceName;

    private String outcome;   // SUCCESS | FAILURE
    private String severity;  // INFO | NOTICE | WARNING | CRITICAL

    @Column(name = "ip_address") private String ipAddress;
    @Column(name = "user_agent") private String userAgent;

    private String detail;

    private String metadata;  // optional JSON context (before/after, request id, etc.)

    /** Tamper-evidence: hash of the previous event and the SHA-256 of this event over prev_hash. */
    @Column(name = "prev_hash") private String prevHash;
    private String hash;

    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Long getSeq() { return seq; }
    public void setSeq(Long seq) { this.seq = seq; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getResourceName() { return resourceName; }
    public void setResourceName(String resourceName) { this.resourceName = resourceName; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public String getPrevHash() { return prevHash; }
    public void setPrevHash(String prevHash) { this.prevHash = prevHash; }
    public String getHash() { return hash; }
    public void setHash(String hash) { this.hash = hash; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
