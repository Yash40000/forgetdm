package io.forgetdm.mapping;

import jakarta.persistence.*;
import java.time.Instant;

/** An ordered pipeline of steps (saved mappings / raw SQL) — Informatica workflow analog. */
@Entity
@Table(name = "mapping_workflows")
public class WorkflowEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String name;
    @Column(columnDefinition = "text") private String description;
    /** JSON array: [{type: MAPPING|SQL, mappingId?, dataSourceId?, sql?, onError: STOP|CONTINUE, label?}] */
    @Column(name = "steps_json", nullable = false, columnDefinition = "text") private String stepsJson;
    /** Most recent run: {status, startedAt, finishedAt, steps:[{label, status, rows, error, elapsedMs}]} */
    @Column(name = "last_run_json", columnDefinition = "text") private String lastRunJson;
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "updated_at") private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public String getStepsJson() { return stepsJson; }
    public void setStepsJson(String v) { stepsJson = v; }
    public String getLastRunJson() { return lastRunJson; }
    public void setLastRunJson(String v) { lastRunJson = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { updatedAt = v; }
}
