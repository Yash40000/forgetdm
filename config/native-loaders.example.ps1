# ForgeTDM native loader configuration template.
# Copy this file to config/native-loaders.local.ps1 and edit only the local file.
# The local file is ignored by git so machine-specific paths and secrets stay private.

# Keep staged native load files for troubleshooting. Default is false.
# $env:FORGETDM_NATIVE_LOAD_KEEP_FILES = "false"

# Native loader timeout. Default is 3600 seconds.
# $env:FORGETDM_NATIVE_LOAD_TIMEOUT_SECONDS = "3600"

# Oracle SQL*Loader
# Install Oracle Instant Client Basic + Tools package, then point this to sqlldr.exe.
# $env:FORGETDM_ORACLE_SQLLOADER_ENABLED = "true"
# $env:FORGETDM_ORACLE_SQLLOADER_BIN = "C:\oracle\instantclient_23_26\sqlldr.exe"
# $env:FORGETDM_ORACLE_SQLLOADER_CONNECT = "//localhost:1521/FREEPDB1"

# SQL Server bcp
# Install Microsoft Command Line Utilities for SQL Server.
# $env:FORGETDM_SQLSERVER_BULK_COPY_ENABLED = "true"
# $env:FORGETDM_SQLSERVER_BULK_COPY_BIN = "C:\Program Files\Microsoft SQL Server\Client SDK\ODBC\170\Tools\Binn\bcp.exe"

# IBM DB2 LOAD
# Install IBM Db2 client / command line processor.
# $env:FORGETDM_DB2_LOAD_ENABLED = "true"
# $env:FORGETDM_DB2_LOAD_BIN = "C:\Program Files\IBM\SQLLIB\BIN\db2.exe"
# $env:FORGETDM_DB2_DATABASE = "YOURDB"

# Snowflake COPY through SnowSQL
# Install SnowSQL and configure a connection in %USERPROFILE%\.snowsql\config.
# $env:FORGETDM_SNOWFLAKE_COPY_ENABLED = "true"
# $env:FORGETDM_SNOWSQL_BIN = "C:\Program Files\Snowflake SnowSQL\snowsql.exe"
# $env:FORGETDM_SNOWSQL_CONNECTION = "forgetdm"

# MySQL LOAD DATA LOCAL
# Install MySQL client tools. The target MySQL server must allow local_infile.
# $env:FORGETDM_MYSQL_LOAD_DATA_ENABLED = "true"
# $env:FORGETDM_MYSQL_LOAD_DATA_BIN = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
