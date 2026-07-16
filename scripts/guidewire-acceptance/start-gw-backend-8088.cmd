@echo off
setlocal

if not defined FORGETDM_DB_URL set "FORGETDM_DB_URL=jdbc:postgresql://localhost:5433/forgetdm"
if not defined FORGETDM_DB_USER set "FORGETDM_DB_USER=forgetdm"
if not defined FORGETDM_DB_PASS set "FORGETDM_DB_PASS=forgetdm"
if not defined FORGETDM_MASKING_SECRET set "FORGETDM_MASKING_SECRET=local-demo-only-change-before-production"

call mvn.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8088" 1>"target\gw-backend.out.log" 2>"target\gw-backend.err.log"
