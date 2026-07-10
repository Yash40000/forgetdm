# ForgeTDM Mainframe Zowe / z/OSMF TLS Testing

This guide explains how to test a ForgeTDM Zowe connection and how to fix common TLS handshake issues when connecting to z/OSMF.

## What ForgeTDM Connects To

ForgeTDM calls the z/OSMF data set REST API over HTTPS:

```text
https://<host>:<port><basePath>/restfiles/ds
```

Typical values:

```text
Host: zosmf.company.com
Port: 443 or 10443
Base path: /zosmf
```

## Quick UI Test

1. Open ForgeTDM Mainframe page.
2. Create a connection with type `ZOWE`.
3. Enter the z/OSMF host, port, base path, username, and password.
4. For dev/test only, check `Trust self-signed cert`.
5. Save the connection.
6. Click `Test`.

Expected result:

```text
OK - <n> file/dataset(s) visible
```

If the test fails before username/password validation, it is usually a TLS/certificate problem.

## Fast Local TLS Test

From PowerShell, test whether the endpoint answers over HTTPS:

```powershell
curl.exe -vk "https://zosmf.company.com:10443/zosmf/restfiles/ds?dslevel=SYS1.*" `
  -u "USER:PASSWORD" `
  -H "X-CSRF-ZOSMF-HEADER: true"
```

How to read the result:

- TLS succeeds but credentials are wrong: you may see `401` or `403`.
- TLS/cert fails: you will see certificate, handshake, protocol, or connection reset errors.
- Wrong port/service: connection may reset or return non-z/OSMF content.

## Common Handshake Errors

### PKIX path building failed

Meaning:

Java does not trust the z/OSMF certificate chain.

Fix:

Import the z/OSMF server certificate or corporate CA certificate into a Java truststore.

### No subject alternative DNS name matching

Meaning:

The host name used in ForgeTDM does not match the certificate SAN/CN.

Fix:

Use the real FQDN from the certificate, not an IP address or alias. If needed, ask the mainframe/security team for a cert with the correct SAN.

### Received fatal alert: handshake_failure

Meaning:

Usually one of these:

- wrong port
- old TLS/cipher configuration on z/OSMF
- client certificate is required
- SSL interception/proxy issue

Fix:

Confirm the z/OSMF HTTPS port, TLS version, and whether mutual TLS is required.

### Connection reset

Meaning:

Usually firewall, wrong port, proxy, or a service that is not z/OSMF.

Fix:

Confirm network path from the ForgeTDM server to z/OSMF.

## Dev/Test Workaround: Trust Self-Signed Cert

ForgeTDM has a checkbox:

```text
Trust self-signed cert
```

Use it only for dev/test. In ForgeTDM this creates a trust-all SSL context for that Zowe call path and disables hostname verification for the Java HTTP client.

Do not use this as the preferred production setup.

## Production Fix: Add z/OSMF Cert To A Truststore

The clean approach is to trust the z/OSMF certificate chain explicitly.

### 1. Export The Certificate

Option A: ask the mainframe/security team for the z/OSMF server certificate or the issuing CA certificate.

Option B: export from browser:

1. Open `https://zosmf.company.com:10443/zosmf`.
2. View certificate.
3. Export as Base-64 encoded `.cer`.

Option C: use OpenSSL:

```powershell
cmd /c "openssl s_client -showcerts -connect zosmf.company.com:10443 -servername zosmf.company.com < NUL > zosmf-certs.pem"
```

Save the server or CA certificate as:

```text
D:\forgetdm-certs\zosmf.cer
```

### 2. Create A ForgeTDM Truststore

Prefer a project truststore instead of modifying the JDK default `cacerts`.

```powershell
New-Item -ItemType Directory -Force D:\forgetdm-certs

keytool -importcert `
  -alias zosmf-company `
  -file D:\forgetdm-certs\zosmf.cer `
  -keystore D:\forgetdm-certs\forgetdm-truststore.p12 `
  -storetype PKCS12 `
  -storepass changeit
```

Answer `yes` when keytool asks whether to trust the certificate.

### 3. Run ForgeTDM With The Truststore

PowerShell:

```powershell
$env:JAVA_TOOL_OPTIONS = "-Djavax.net.ssl.trustStore=D:\forgetdm-certs\forgetdm-truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12"

mvn spring-boot:run
```

If you also use the ForgeTDM database environment variables:

```powershell
$env:FORGETDM_DB_URL  = "jdbc:postgresql://localhost:5433/forgetdm"
$env:FORGETDM_DB_USER = "forgetdm"
$env:FORGETDM_DB_PASS = "forgetdm"
$env:FORGETDM_MASKING_SECRET = "pick-a-long-random-secret"
$env:JAVA_TOOL_OPTIONS = "-Djavax.net.ssl.trustStore=D:\forgetdm-certs\forgetdm-truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12"

mvn spring-boot:run
```

### 4. Retest In ForgeTDM

1. Open Mainframe page.
2. Edit/create Zowe connection.
3. Leave `Trust self-signed cert` unchecked.
4. Click `Test`.

Expected result:

```text
OK - <n> file/dataset(s) visible
```

## TLS Debug Logging

If the error is still unclear, enable Java SSL debug:

```powershell
$env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Djavax.net.debug=ssl,handshake"
mvn spring-boot:run
```

Then retest the Zowe connection and inspect the console logs.

Turn it off after troubleshooting because SSL debug logs are noisy and may expose connection metadata.

## Mutual TLS / Client Certificate

Some mainframe environments require a client certificate in addition to username/password.

Current ForgeTDM Zowe support uses HTTPS plus basic authentication. If the client requires mutual TLS, ForgeTDM needs keystore/client-certificate support added for the Zowe transport.

Symptoms of required mutual TLS:

- handshake failure even after importing the server CA certificate
- mainframe security team says the endpoint requires client authentication
- TLS debug logs mention client certificate request or no suitable certificate

## Best Checklist

Use this order:

1. Confirm host is the real z/OSMF FQDN.
2. Confirm HTTPS port.
3. Confirm base path is `/zosmf`.
4. Try ForgeTDM with `Trust self-signed cert` for dev/test.
5. If that works, import the real certificate into a truststore.
6. Retest with `Trust self-signed cert` unchecked.
7. If still failing, check TLS version/cipher and mutual TLS requirement.

