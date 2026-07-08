package io.forgetdm.virtualization;

import jakarta.persistence.*;
import java.time.Instant;

/** A "target environment": an SSH-reachable host that mounts NFS-exported clones and runs VDBs. */
@Entity
@Table(name = "target_environments")
public class TargetEnvironmentEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String name;
    @Column(nullable = false) private String host;
    @Column(name = "ssh_user", nullable = false) private String sshUser = "root";
    @Column(name = "ssh_port", nullable = false) private int sshPort = 22;
    @Column(name = "mount_base", nullable = false) private String mountBase = "/mnt/forgetdm";
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public String getSshUser() { return sshUser; }
    public void setSshUser(String sshUser) { this.sshUser = sshUser; }
    public int getSshPort() { return sshPort; }
    public void setSshPort(int sshPort) { this.sshPort = sshPort; }
    public String getMountBase() { return mountBase; }
    public void setMountBase(String mountBase) { this.mountBase = mountBase; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
