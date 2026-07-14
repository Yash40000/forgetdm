# Synthetic scenario pack

The normal regression suite stays fast:

```powershell
mvn test
```

The supplied banking-scale scenario pack is intentionally opt-in because it resets the
`synthetic_auto_test` schema and loads more than 1.9 million PostgreSQL rows:

```powershell
$env:FORGETDM_DB_URL = "jdbc:postgresql://localhost:5433/forgetdm"
$env:FORGETDM_DB_USER = "forgetdm"
$env:FORGETDM_DB_PASS = "forgetdm"
mvn "-Dtest=SyntheticScenarioPackTest" "-Dforgetdm.runSyntheticScenarioPack=true" test
```

The run updates `synthetic_test_results.md` with pass/fail evidence. Do not point the pack
at a shared or production database.
