package io.forgetdm.provision;

import jakarta.persistence.*;
import java.time.Instant;

/** A named reference-data value list (e.g. bank-a.product_type = A1|A2|A3), referenced from generators as @name. */
@Entity
@Table(name = "synthetic_value_lists")
public class ValueListEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String name;
    private String description;
    @Column(name = "system_tag") private String systemTag;
    /** Pipe syntax: {@code A1|A2|A3} or weighted {@code A1:60|A2:30|A3:10}. */
    @Column(name = "list_values", nullable = false, columnDefinition = "text") private String listValues;
    @Column(name = "owner_username") private String ownerUsername;
    @Column(nullable = false) private String visibility = "GLOBAL";   // GLOBAL | PRIVATE
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "updated_at") private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public String getSystemTag() { return systemTag; }
    public void setSystemTag(String v) { systemTag = v; }
    public String getListValues() { return listValues; }
    public void setListValues(String v) { listValues = v; }
    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String v) { ownerUsername = v; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String v) { visibility = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { updatedAt = v; }
}
