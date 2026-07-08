package io.forgetdm.policy;

import jakarta.persistence.*;
import java.time.Instant;

/** A user-defined Lua masking script (Optim-style exit), referenced from rules as function SCRIPT + param1 = name. */
@Entity
@Table(name = "masking_scripts")
public class MaskingScriptEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String name;
    private String description;
    @Column(name = "lua_source", nullable = false, columnDefinition = "text") private String luaSource;
    @Column(name = "owner_username") private String ownerUsername;
    /** GLOBAL = usable in jobs; PRIVATE = draft, visible/editable by owner only, not resolvable in jobs. */
    @Column(nullable = false) private String visibility = "GLOBAL";
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "updated_at") private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public String getLuaSource() { return luaSource; }
    public void setLuaSource(String v) { luaSource = v; }
    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String v) { ownerUsername = v; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String v) { visibility = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { updatedAt = v; }
}
