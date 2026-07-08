# ForgeTDM Local Startup and Native Loaders

You do not need to remember native-loader environment variables.

Use this command from the project root:

```powershell
.\start-forgetdm.ps1
```

The script loads:

- `config/local.env.ps1` for the ForgeTDM database, user, password, and masking secret.
- `config/native-loaders.local.ps1` for optional Oracle, SQL Server, DB2, Snowflake, and MySQL loader paths.

Template files are included:

- `config/local.env.example.ps1`
- `config/native-loaders.example.ps1`

Private local files are ignored by git:

- `config/local.env.ps1`
- `config/native-loaders.local.ps1`

After the app starts, open Data Sources and check the `Native loader health` panel. A loader can be:

- `READY`: the vendor client is installed and enabled.
- `MISSING BINARY`: the enable flag is on, but the executable path is wrong or missing.
- `FALLBACK`: native loading is off, so ForgeTDM uses the JDBC fallback.

Postgres COPY does not need local setup; it is built in through the PostgreSQL driver.
