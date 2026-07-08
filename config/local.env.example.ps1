# ForgeTDM local app environment template.
# Copy to config/local.env.ps1 and edit the local file.

$env:FORGETDM_DB_URL = "jdbc:postgresql://localhost:5433/forgetdm"
$env:FORGETDM_DB_USER = "forgetdm"
$env:FORGETDM_DB_PASS = "forgetdm"
$env:FORGETDM_MASKING_SECRET = "pick-a-long-random-secret"

# Optional app port. Leave blank to use application defaults.
# $env:SERVER_PORT = "8088"
