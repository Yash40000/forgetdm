package io.forgetdm.businessentity;

import jakarta.persistence.*;
import java.time.Instant;

/** Immutable lineage trail entry for an entity instance capsule (materialize, access, provision, retire, ...). */
@Entity
@Table(name = "be_entity_lineage_events")
public class BeEntityLineageEventEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "instance_id", nullable = false) private Long instanceId;
    @Column(name = "event_type", nullable = false) private String eventType;
    @Column(name = "detail_json", columnDefinition = "text") private String detailJson;
    private String actor;
    @Column(name = "occurred_at") private Instant occurredAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getInstanceId() { return instanceId; }
    public void setInstanceId(Long v) { instanceId = v; }
    public String getEventType() { return eventType; }
    public void setEventType(String v) { eventType = v; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String v) { detailJson = v; }
    public String getActor() { return actor; }
    public void setActor(String v) { actor = v; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant v) { occurredAt = v; }
}
