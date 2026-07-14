# ForgeTDM Private Story-to-Data AI

ForgeTDM can turn a user story or test case into a grounded, reviewable test-data plan without
sending production data to an external LLM. The feature is available under **Auto Provision >
Story to Data**. Its grounding catalog is visible under **Forge Data Store**.

## What the private model does

The model performs one bounded task: it extracts typed intent from business language. The output
must match ForgeTDM's JSON schema. It may identify capabilities, business conditions, requested
volume, privacy intent, target hints, validation expectations, and unresolved questions.

The model does not select database IDs, write SQL, approve policies, or execute jobs. It sees only
metadata and sanitized evidence from the Forge Data Store. Retrieved content is treated as
untrusted reference text so it cannot override the system guardrails.

## What ForgeTDM does deterministically

After intent extraction, the server resolves exact data sources, environments, Business Entities,
DataScopes, masking policies, saved jobs, and self-service products. It then:

1. Blocks ambiguous source, target, data product, or masking-policy references.
2. Refuses production targets and unapproved unmasked-data requests.
3. Creates an immutable plan fingerprint and evidence citations.
4. Requires independent maker-checker plan approval before execution; creators cannot self-approve.
5. Requires a second explicit approval for every data-changing action.
6. Executes only actions registered in the governed ForgeTDM action catalog.
7. Persists steps, results, approvals, failures, corrections, and actor/time evidence.

If the private model is offline or returns invalid JSON, ForgeTDM uses a deterministic fallback
extractor and marks the plan with a visible warning. No action runs during plan compilation.

## Forge Data Store

The Forge Data Store is a versioned metadata and knowledge layer, not a copy of production data.
Synchronization currently indexes:

- data sources and their role/environment metadata;
- DataScope definitions and table-profile metadata;
- masking policies and approved rule metadata;
- Business Entity models and application participation;
- classified PII table metadata;
- mapping definitions;
- synthetic and DataScope saved jobs;
- self-service data products;
- approved Story-to-Data plans;
- steward-managed glossary, domain rules, testing standards, and operating guidance.

Passwords, JDBC URLs, connection secrets, and sampled row values are deliberately excluded. Each
record receives a stable `FDS-<id>` citation. Re-synchronization updates version/hash metadata while
preserving user-managed knowledge.

## Local runtime setup

### Ollama for workstation testing

```powershell
ollama serve
ollama pull llama3.2:3b
$env:FORGETDM_AI_PROVIDER = "ollama"
mvn.cmd spring-boot:run
```

ForgeTDM calls Ollama's OpenAI-compatible endpoint at `http://localhost:11434/v1`. It checks
`/models` before use. If `OLLAMA_MODEL` names a model that is not installed, ForgeTDM selects an
installed local model and reports the selection in the workbench. Set `OLLAMA_MODEL` to pin a
specific model in controlled environments. Workstation inference is bounded to 20 seconds by
default (`FORGETDM_AI_LOCAL_TIMEOUT`); ForgeTDM then compiles through its visible deterministic
fallback instead of leaving the user waiting on an undersized model.

### vLLM for enterprise deployment

Serve an instruction model behind an internal OpenAI-compatible vLLM endpoint, then set:

```powershell
$env:VLLM_MODEL = "your-approved-model"
$env:VLLM_BASE_URL = "http://your-private-ai-host:8000/v1"
$env:FORGETDM_AI_PROVIDER = "vllm"
```

Place the endpoint behind the organization's normal TLS, identity, network-segmentation, model
registry, and change-control processes. Do not expose the inference endpoint publicly.

## Operating flow

1. A TDM architect opens **Forge Data Store** and synchronizes metadata.
2. The architect adds governed glossary or domain rules when business terminology needs context.
3. A user opens **Auto Provision > Story to Data** and describes the desired test outcome.
4. ForgeTDM displays extracted intent, exact evidence, confidence, risks, blockers, and executable
   steps.
5. The user resolves blockers by revising the story. The prior plan remains immutable in history.
6. A different authorized user with provisioning approval permission approves the frozen plan.
7. ForgeTDM runs safe steps and pauses at each data-changing action for confirmation.
8. Results and events remain available in the durable run history.
9. Users accept the plan or submit a correction. Feedback is retained for steward review and a
   future approved local fine-tuning dataset; it is not learned automatically.

## Production acceptance gates

Before enabling this in a banking environment, validate the approved local model against a bank-
specific benchmark covering ambiguous stories, cross-application identity, masking requirements,
negative cases, production-target refusal, prompt injection, missing metadata, and deliberately
conflicting instructions. Measure intent accuracy, artifact-resolution precision, blocker recall,
unsafe-action rate, and operator correction rate. A model upgrade must pass the same benchmark
before promotion.
