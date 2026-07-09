package io.forgetdm.businessentity;

import jakarta.persistence.*;
import java.time.Instant;

/** Governance access grant scoped to a whole Business Entity or a single instance capsule. */
@Entity
@Table(name = "be_entity_access_grants")
public class BeEntityAccessGrantEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "entity_id", nullable = false) private Long entityId;
    @Column(name = "instance_id") private Long instanceId;
    @Column(name = "grantee_type", nullable = false) private String granteeType = "USER";
    @Column(nullable = false) private String grantee;
    @Column(nullable = false) private String scope = "READ";
    @Column(name = "granted_by") private String grantedBy;
    @Column(name = "granted_at") private Instant grantedAt = Instant.now();
    @Column(name = "expires_at") private Instant expiresAt;
    @Column(nullable = false) private boolean revoked = false;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Column(name = "revoked_by") private String revokedBy;
    @Column(columnDefinition = "text") private String note;

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getEntityId() { return entityId; }
    public void setEntityId(Long v) { entityId = v; }
    public Long getInstanceId() { return instanceId; }
    public void setInstanceId(Long v) { instanceId = v; }
    public String getGranteeType() { return granteeType; }
    public void setGranteeType(String v) { granteeType = v; }
    public String getGrantee() { return grantee; }
    public void setGrantee(String v) { grantee = v; }
    public String getScope() { return scope; }
    public void setScope(String v) { scope = v; }
    public String getGrantedBy() { return grantedBy; }
    public void setGrantedBy(String v) { grantedBy = v; }
    public Instant getGrantedAt() { return grantedAt; }
    public void setGrantedAt(Instant v) { grantedAt = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { expiresAt = v; }
    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean v) { revoked = v; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant v) { revokedAt = v; }
    public String getRevokedBy() { return revokedBy; }
    public void setRevokedBy(String v) { revokedBy = v; }
    public String getNote() { return note; }
    public void setNote(String v) { note = v; }
}
