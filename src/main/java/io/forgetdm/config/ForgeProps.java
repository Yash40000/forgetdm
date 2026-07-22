package io.forgetdm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "forgetdm")
public class ForgeProps {
    private String maskingSecret = "change-me-in-production";
    /** Master secret for Micro-DB capsule payload encryption (AES-256-GCM key derivation). Override in prod! */
    private String capsuleSecret = "change-me-in-production";
    private Provisioning provisioning = new Provisioning();
    private Discovery discovery = new Discovery();
    private Virtualization virtualization = new Virtualization();
    private Governance governance = new Governance();
    private Vault vault = new Vault();
    private Cdc cdc = new Cdc();
    private Staging staging = new Staging();

    /** Zero-trust encrypted staging (RFP §3.1.2): extracted source data is encrypted at rest in the
     *  storage pool with a key derived from the (Vault-held) masking secret — only the engine can
     *  decrypt it. Off by default; opt in per deployment. */
    public static class Staging {
        private boolean encrypt = false;
        public boolean isEncrypt() { return encrypt; }
        public void setEncrypt(boolean v) { encrypt = v; }
    }

    /** Continuous CDC capture: a background poller keeps each active slot's confirmed position close
     *  to current, so decoding stays cheap and idle slots stop pinning WAL. */
    public static class Cdc {
        private boolean continuousEnabled = true;    // ACTIVE means continuously captured unless explicitly disabled
        private long intervalMs = 30_000;            // delay between poll sweeps
        public boolean isContinuousEnabled() { return continuousEnabled; }
        public void setContinuousEnabled(boolean v) { continuousEnabled = v; }
        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long v) { intervalMs = v; }
    }

    /**
     * HashiCorp Vault as the source of the masking key/salt (RFP §3.2.3). When enabled, the masking
     * secret is read from Vault at startup instead of the local {@code forgetdm.masking-secret}
     * property, centralising key custody. Referential integrity requires a stable key, so pin a KV
     * version in production before any rotation (rotating the salt re-keys deterministic masking).
     */
    public static class Vault {
        private boolean enabled = false;
        private String address = "http://127.0.0.1:8200";
        private String token = "";
        private String namespace = "";            // Vault Enterprise namespace (optional)
        private String kvMount = "secret";        // KV secrets-engine mount
        private int kvVersion = 2;                // 1 or 2
        private String path = "forgetdm/masking"; // secret path under the mount
        private String field = "maskingSecret";   // key within the secret holding the salt
        private boolean failClosed = false;       // true: fail startup if Vault is unreachable (recommended in prod)
        private int timeoutMs = 4000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { enabled = v; }
        public String getAddress() { return address; }
        public void setAddress(String v) { address = v; }
        public String getToken() { return token; }
        public void setToken(String v) { token = v; }
        public String getNamespace() { return namespace; }
        public void setNamespace(String v) { namespace = v; }
        public String getKvMount() { return kvMount; }
        public void setKvMount(String v) { kvMount = v; }
        public int getKvVersion() { return kvVersion; }
        public void setKvVersion(int v) { kvVersion = v; }
        public String getPath() { return path; }
        public void setPath(String v) { path = v; }
        public String getField() { return field; }
        public void setField(String v) { field = v; }
        public boolean isFailClosed() { return failClosed; }
        public void setFailClosed(boolean v) { failClosed = v; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int v) { timeoutMs = v; }
    }

    public static class Virtualization {
        private Zfs zfs = new Zfs();
        public Zfs getZfs() { return zfs; }
        public void setZfs(Zfs z) { zfs = z; }
    }

    public static class Zfs {
        private String host = "";                 // empty = run zfs/docker locally
        private String sshUser = "root";
        private int sshPort = 22;
        private boolean useSudo = false;          // run engine commands via passwordless sudo (non-root SSH user)
        private String dockerNetwork = "";        // e.g. "host" when the default bridge can't reach the source LAN
        private String pool = "tank/forgetdm";    // parent dataset
        private String localhostAlias = "";       // engine-visible address of "localhost" sources
        private String mssqlSourceBackupDir = "/var/opt/mssql/backup"; // BACKUP ... TO DISK path on the SQL Server host
        private String mssqlBackupMount = "";     // engine path where that backup dir is mounted/reachable
        private String mssqlImage = "mcr.microsoft.com/mssql/server:2022-latest";
        // DB2 LUW / DB2 z/OS — VDB container (logical snapshot → real DB2 engine)
        private String db2Image = "icr.io/db2_community/db2:11.5.9.0";
        private String db2InstancePassword = "Forgetdm!Str0ng1";
        // Oracle — VDB container (logical snapshot → real Oracle Free engine)
        private String oracleImage = "gvenzl/oracle-free:23-slim";
        private String oracleSysPassword = "Forgetdm1Oracle";

        public String getHost() { return host; }
        public void setHost(String v) { host = v; }
        public String getSshUser() { return sshUser; }
        public void setSshUser(String v) { sshUser = v; }
        public int getSshPort() { return sshPort; }
        public void setSshPort(int v) { sshPort = v; }
        public boolean isUseSudo() { return useSudo; }
        public void setUseSudo(boolean v) { useSudo = v; }
        public String getDockerNetwork() { return dockerNetwork; }
        public void setDockerNetwork(String v) { dockerNetwork = v; }
        public String getPool() { return pool; }
        public void setPool(String v) { pool = v; }
        public String getLocalhostAlias() { return localhostAlias; }
        public void setLocalhostAlias(String v) { localhostAlias = v; }
        public String getMssqlSourceBackupDir() { return mssqlSourceBackupDir; }
        public void setMssqlSourceBackupDir(String v) { mssqlSourceBackupDir = v; }
        public String getMssqlBackupMount() { return mssqlBackupMount; }
        public void setMssqlBackupMount(String v) { mssqlBackupMount = v; }
        public String getMssqlImage() { return mssqlImage; }
        public void setMssqlImage(String v) { mssqlImage = v; }
        public String getDb2Image() { return db2Image; }
        public void setDb2Image(String v) { db2Image = v; }
        public String getDb2InstancePassword() { return db2InstancePassword; }
        public void setDb2InstancePassword(String v) { db2InstancePassword = v; }
        public String getOracleImage() { return oracleImage; }
        public void setOracleImage(String v) { oracleImage = v; }
        public String getOracleSysPassword() { return oracleSysPassword; }
        public void setOracleSysPassword(String v) { oracleSysPassword = v; }
    }

    public static class Provisioning {
        private int batchSize = 5000;
        private int workerThreads = 4;
        /** Days after finishedAt before a completed/failed/canceled job is auto-purged. 0 = disabled. */
        private int jobRetentionDays = 90;
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int v) { batchSize = v; }
        public int getWorkerThreads() { return workerThreads; }
        public void setWorkerThreads(int v) { workerThreads = v; }
        public int getJobRetentionDays() { return jobRetentionDays; }
        public void setJobRetentionDays(int v) { jobRetentionDays = v; }
    }
    public static class Discovery {
        private int sampleRows = 100;
        public int getSampleRows() { return sampleRows; }
        public void setSampleRows(int v) { sampleRows = v; }
    }

    /** Maker-checker governance for real-data provisioning (MASK_COPY / SUBSET_MASK). */
    public static class Governance {
        /** prod-only (default): approval required when the SOURCE is tagged PROD; always; never. */
        private String requireProvisionApproval = "prod-only";
        /** Also require approval when approved-PII columns in the DataScope have no masking. */
        private boolean requireApprovalOnUnmaskedPii = false;
        /** Hard-block submitting a PROD-source job that has no masking policy anywhere. */
        private boolean blockUnmaskedProdCopy = true;
        /** Require an attached Micro-DB capsule (with a valid access grant) on Business Entity execution plans. */
        private boolean requireCapsuleOnExecutionPlans = false;
        public String getRequireProvisionApproval() { return requireProvisionApproval; }
        public void setRequireProvisionApproval(String v) { requireProvisionApproval = v; }
        public boolean isRequireApprovalOnUnmaskedPii() { return requireApprovalOnUnmaskedPii; }
        public void setRequireApprovalOnUnmaskedPii(boolean v) { requireApprovalOnUnmaskedPii = v; }
        public boolean isBlockUnmaskedProdCopy() { return blockUnmaskedProdCopy; }
        public void setBlockUnmaskedProdCopy(boolean v) { blockUnmaskedProdCopy = v; }
        public boolean isRequireCapsuleOnExecutionPlans() { return requireCapsuleOnExecutionPlans; }
        public void setRequireCapsuleOnExecutionPlans(boolean v) { requireCapsuleOnExecutionPlans = v; }
    }

    public String getMaskingSecret() { return maskingSecret; }
    public void setMaskingSecret(String v) { maskingSecret = v; }
    public String getCapsuleSecret() { return capsuleSecret; }
    public void setCapsuleSecret(String v) { capsuleSecret = v; }
    public Provisioning getProvisioning() { return provisioning; }
    public void setProvisioning(Provisioning p) { provisioning = p; }
    public Discovery getDiscovery() { return discovery; }
    public void setDiscovery(Discovery d) { discovery = d; }
    public Virtualization getVirtualization() { return virtualization; }
    public void setVirtualization(Virtualization v) { virtualization = v; }
    public Governance getGovernance() { return governance; }
    public void setGovernance(Governance g) { governance = g; }
    public Vault getVault() { return vault; }
    public void setVault(Vault v) { vault = v; }
    public Cdc getCdc() { return cdc; }
    public void setCdc(Cdc c) { cdc = c; }
    public Staging getStaging() { return staging; }
    public void setStaging(Staging s) { staging = s; }
}
