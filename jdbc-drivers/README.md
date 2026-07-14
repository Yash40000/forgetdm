# Optional JDBC drivers

ForgeTDM bundles PostgreSQL, MySQL, MariaDB, Oracle, SQL Server, DB2/JCC, and H2 drivers.

For licensed or separately distributed drivers (for example Teradata, Sybase, SAP HANA,
Snowflake, BigQuery, or Redshift), place the vendor JDBC jar and its required dependency jars
in this directory before starting ForgeTDM. Jar files are intentionally ignored by Git.

To use a different directory:

```powershell
$env:FORGETDM_JDBC_DRIVER_DIR = "D:\forge-drivers"
mvn spring-boot:run
```

The jar must publish a `java.sql.Driver` service provider in
`META-INF/services/java.sql.Driver`. Loaded drivers are visible at `GET /api/datasources/drivers`.
After registering a connection, use **Data Sources -> Diagnose** to run the schema preflight.

Only install drivers supplied and approved by your database vendor. A JDBC driver executes in
the ForgeTDM process and must be treated as application code.
