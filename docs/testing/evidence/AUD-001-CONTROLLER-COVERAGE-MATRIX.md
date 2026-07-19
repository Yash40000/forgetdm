# AUD-001 Controller Audit Coverage Matrix

Date reviewed: 2026-07-18
Authority: current `src/main/java` working tree (HEAD `16b6e3758ae249aaa3ed85ee89f90b82762cf2c4`, including current uncommitted source changes)
Scope: every controller `POST`, `PUT`, `PATCH`, and `DELETE` endpoint, plus material `GET` exports/downloads.

## Classification rules

- **COVERED** - the endpoint's actual controller/service path emits a structured event with actor, action, resource type/id/name, explicit outcome, timestamp, and safe detail/metadata where relevant. `AuditWriter` supplies the timestamp at `src/main/java/io/forgetdm/audit/AuditWriter.java:50` (or `:69` for a chain anchor).
- **PARTIAL** - an event exists, but it is emitted through the legacy three-argument `audit.log(actor, action, detail)` API at `src/main/java/io/forgetdm/audit/AuditService.java:47`, is conditional/success-only, or otherwise lacks explicit resource identity/outcome at the call site.
- **GAP** - the material endpoint changes persisted or external state, or exports protected information, but its actual path emits no endpoint-specific audit event.
- **N/A-read-only** - the verb is non-GET but the implementation only parses, validates, previews, probes an unsaved definition, or reads data without changing business/external state. These rows are retained so POST-based reads are not mistaken for omissions.

`AuditService.log` currently infers category, resource, and outcome from action/detail. That inference does not meet the strict AUD-001 proof rule: it is not an explicit identity/outcome contract at the emitting call site. The current tree contains **151 legacy `audit.log` call sites**; **125 endpoint rows below rely wholly or partly on them**.

## Totals

| Module | Scoped endpoints | COVERED | PARTIAL | GAP | N/A-read-only |
|---|---:|---:|---:|---:|---:|
| AI and agent | 14 | 0 | 9 | 4 | 1 |
| Audit ledger | 2 | 2 | 0 | 0 | 0 |
| Automation and self-service | 20 | 0 | 16 | 4 | 0 |
| Business entity | 42 | 0 | 39 | 1 | 2 |
| Copybook utilities | 4 | 0 | 0 | 0 | 4 |
| Dataset and DataScope versioning | 20 | 4 | 5 | 10 | 1 |
| Data sources | 5 | 4 | 0 | 0 | 1 |
| Discovery | 12 | 2 | 9 | 0 | 1 |
| Mainframe | 12 | 4 | 0 | 7 | 1 |
| Mapping and workflows | 18 | 7 | 8 | 0 | 3 |
| Masking policy and scripts | 9 | 6 | 2 | 0 | 1 |
| Provisioning and synthetic | 33 | 14 | 12 | 2 | 5 |
| Query, subset, reservations, and RI | 10 | 0 | 8 | 0 | 2 |
| Security and authentication | 10 | 8 | 2 | 0 | 0 |
| Unstructured masking | 7 | 4 | 2 | 0 | 1 |
| Validation | 3 | 0 | 2 | 0 | 1 |
| Virtualization | 12 | 0 | 11 | 1 | 0 |
| **Total** | **233** | **55** | **125** | **29** | **24** |

## AI and agent

| Endpoint and controller | Audit event(s) actually emitted | Status | Finding |
|---|---|---|---|
| `POST /api/ai/chat` - `src/main/java/io/forgetdm/ai/AiController.java:25` | None | **N/A-read-only** | LLM conversation only; no confirmed tool action is executed. |
| `POST /api/ai/act` - `src/main/java/io/forgetdm/ai/AiController.java:30` | Legacy `AI_ACTION_EXECUTED` / `AI_ACTION_FAILED` - `src/main/java/io/forgetdm/ai/AiAssistantService.java:179`, `:181` | **PARTIAL** | Both outcomes exist, but resource identity/outcome are inferred from free-form arguments/error text. |
| `POST /api/agent/plan` - `src/main/java/io/forgetdm/ai/AgentController.java:29` | Legacy `AGENT_PLAN_COMPILED` - `src/main/java/io/forgetdm/ai/AgentService.java:49` | **PARTIAL** | Run id is embedded in detail; outcome and resource are not structured. |
| `POST /api/agent/runs/{id}/next` - `src/main/java/io/forgetdm/ai/AgentController.java:42` | Legacy `AGENT_ACTION_EXECUTED` / `AGENT_ACTION_FAILED` and possible `AGENT_RUN_COMPLETED` - `src/main/java/io/forgetdm/ai/AgentService.java:129`, `:136`, `:192` | **PARTIAL** | Lifecycle is legacy and action identity is free-form. |
| `POST /api/agent/runs/{id}/run` - `src/main/java/io/forgetdm/ai/AgentController.java:45` | Same legacy lifecycle - `src/main/java/io/forgetdm/ai/AgentService.java:129`, `:136`, `:192` | **PARTIAL** | No structured run/action resource contract. |
| `POST /api/agent/runs/{id}/approve-plan` - `src/main/java/io/forgetdm/ai/AgentController.java:48` | Legacy `AGENT_PLAN_APPROVED` - `src/main/java/io/forgetdm/ai/AgentService.java:62` | **PARTIAL** | Approval event lacks explicit resource/outcome fields. |
| `POST /api/agent/runs/{id}/approve` - `src/main/java/io/forgetdm/ai/AgentController.java:51` | Legacy `AGENT_ACTION_EXECUTED` / `AGENT_ACTION_FAILED` - `src/main/java/io/forgetdm/ai/AgentService.java:129`, `:136` | **PARTIAL** | Approval decision itself is not a structured event. |
| `POST /api/agent/runs/{id}/reject` - `src/main/java/io/forgetdm/ai/AgentController.java:54` | None | **GAP** | Persisted step rejection/skip is recorded only in agent-local event storage, not the audit ledger. |
| `POST /api/agent/runs/{id}/cancel` - `src/main/java/io/forgetdm/ai/AgentController.java:57` | Legacy `AGENT_RUN_CANCELED` - `src/main/java/io/forgetdm/ai/AgentService.java:156` | **PARTIAL** | Run id is detail text; no explicit outcome. |
| `POST /api/agent/runs/{id}/revise` - `src/main/java/io/forgetdm/ai/AgentController.java:60` | Legacy `AGENT_PLAN_COMPILED` for the replacement run - `src/main/java/io/forgetdm/ai/AgentService.java:49` | **PARTIAL** | Superseding the original run has no audit event; only the new plan is logged. |
| `POST /api/agent/runs/{id}/feedback` - `src/main/java/io/forgetdm/ai/AgentController.java:65` | Legacy `AGENT_PLAN_FEEDBACK` - `src/main/java/io/forgetdm/ai/AgentService.java:172` | **PARTIAL** | Feedback resource/outcome are not structured. |
| `POST /api/agent/data-store/sync` - `src/main/java/io/forgetdm/ai/AgentController.java:78` | None | **GAP** | Rewrites/activates grounding documents and sync state without ledger evidence. |
| `POST /api/agent/data-store/documents` - `src/main/java/io/forgetdm/ai/AgentController.java:89` | None | **GAP** | Creates a manual grounding document without audit evidence. |
| `DELETE /api/agent/data-store/documents/{id}` - `src/main/java/io/forgetdm/ai/AgentController.java:95` | None | **GAP** | Deletes or excludes a grounding document without audit evidence. |

Module total: **14 = 0 COVERED + 9 PARTIAL + 4 GAP + 1 N/A-read-only**.

## Audit ledger

| Endpoint and controller | Audit event(s) actually emitted | Status | Finding |
|---|---|---|---|
| `POST /api/audit/reanchor` - `src/main/java/io/forgetdm/audit/AuditController.java:85` | Structured anchor `AUDIT_CHAIN_REANCHORED` - `src/main/java/io/forgetdm/audit/AuditService.java:221`, event fields at `:242` | **COVERED** | Actor, ledger resource, SUCCESS outcome, reason/digest metadata, and anchor timestamp are explicit. |
| `GET /api/audit/export.csv` - `src/main/java/io/forgetdm/audit/AuditController.java:93` | Structured `AUDIT_EXPORTED` - `src/main/java/io/forgetdm/audit/AuditController.java:115` | **COVERED** | Export identity, actor, outcome, filters/count metadata, and timestamp are present. |

Module total: **2 = 2 COVERED**.

## Automation and self-service

| Endpoint and controller | Audit event(s) actually emitted | Status | Finding |
|---|---|---|---|
| `PUT /api/self-service/templates/{id}` - `src/main/java/io/forgetdm/automation/SelfServiceController.java:15` | Legacy `SELF_SERVICE_TEMPLATE_PUBLISHED` / `...UNPUBLISHED` - `src/main/java/io/forgetdm/automation/SelfServiceService.java:62` | **PARTIAL** | Template id is detail text; no explicit outcome/resource fields. |
| `POST /api/self-service/requests` - `src/main/java/io/forgetdm/automation/SelfServiceController.java:18` | Legacy `SELF_SERVICE_REQUESTED` - `src/main/java/io/forgetdm/automation/SelfServiceService.java:80` | **PARTIAL** | Request/template identities are not structured. |
| `POST /api/self-service/requests/{id}/decision/approve` - `src/main/java/io/forgetdm/automation/SelfServiceController.java:19` | Legacy dynamic `SELF_SERVICE_APPROVED` - `src/main/java/io/forgetdm/automation/SelfServiceService.java:107` | **PARTIAL** | Decision outcome is encoded in action text, not explicit outcome/metadata. |
| `POST /api/self-service/requests/{id}/decision/reject` - `src/main/java/io/forgetdm/automation/SelfServiceController.java:20` | Legacy dynamic `SELF_SERVICE_REJECTED` - `src/main/java/io/forgetdm/automation/SelfServiceService.java:107` | **PARTIAL** | Same legacy limitation. |
| `POST /api/self-service/requests/{id}/fulfill` - `src/main/java/io/forgetdm/automation/SelfServiceController.java:21` | Legacy `SELF_SERVICE_FULFILLED` - `src/main/java/io/forgetdm/automation/SelfServiceService.java:125` | **PARTIAL** | Request/run identity is free-form detail. |
| `POST /api/integrations` - `src/main/java/io/forgetdm/automation/IntegrationController.java:21` | Legacy `INTEGRATION_ENDPOINT_CREATED` - `src/main/java/io/forgetdm/automation/IntegrationWebhookService.java:89` | **PARTIAL** | Endpoint identity/outcome not structured. |
| `PUT /api/integrations/{id}` - `src/main/java/io/forgetdm/automation/IntegrationController.java:22` | Legacy `INTEGRATION_ENDPOINT_UPDATED` - `src/main/java/io/forgetdm/automation/IntegrationWebhookService.java:94` | **PARTIAL** | Same limitation. |
| `DELETE /api/integrations/{id}` - `src/main/java/io/forgetdm/automation/IntegrationController.java:23` | Legacy `INTEGRATION_ENDPOINT_DELETED` - `src/main/java/io/forgetdm/automation/IntegrationWebhookService.java:102` | **PARTIAL** | Same limitation. |
| `POST /api/integrations/{id}/test` - `src/main/java/io/forgetdm/automation/IntegrationController.java:24` | None | **GAP** | Creates an integration outbox delivery at `IntegrationWebhookService.java:138` but no ledger event. |
| `POST /api/integrations/deliveries/{id}/retry` - `src/main/java/io/forgetdm/automation/IntegrationController.java:25` | Legacy `INTEGRATION_DELIVERY_RETRIED` - `src/main/java/io/forgetdm/automation/IntegrationWebhookService.java:162` | **PARTIAL** | Delivery/endpoint ids are detail text; outcome is inferred. |
| `POST /api/self-service/v2/products` - `src/main/java/io/forgetdm/automation/EnterpriseSelfServiceController.java:23` | Legacy `SELF_SERVICE_PRODUCT_PUBLISHED` - `src/main/java/io/forgetdm/automation/EnterpriseSelfServiceService.java:125` | **PARTIAL** | Product/artifact identity is not structured. |
| `POST /api/self-service/v2/products/{id}/enable` - `src/main/java/io/forgetdm/automation/EnterpriseSelfServiceController.java:24` | Legacy `SELF_SERVICE_PRODUCT_ENABLED` - `src/main/java/io/forgetdm/automation/EnterpriseSelfServiceService.java:133` | **PARTIAL** | Resource/outcome inferred. |
| `POST /api/self-service/v2/products/{id}/disable` - `src/main/java/io/forgetdm/automation/EnterpriseSelfServiceController.java:25` | Legacy `SELF_SERVICE_PRODUCT_DISABLED` - `src/main/java/io/forgetdm/automation/EnterpriseSelfServiceService.java:133` | **PARTIAL** | Resource/outcome inferred. |
| `POST /api/self-service/v2/orders` - `src/main/java/io/forgetdm/automation/EnterpriseSelfServiceController.java:29` | Legacy `SELF_SERVICE_ORDER_REQUESTED` - `src/main/java/io/forgetdm/automation/EnterpriseSelfServiceService.java:167` | **PARTIAL** | Order/product identity is free-form. |
| `POST /api/self-service/v2/orders/{id}/decision/approve` - `src/main/java/io/forgetdm/automation/EnterpriseSelfServiceController.java:30` | Legacy dynamic `SELF_SERVICE_ORDER_APPROVED` - `src/main/java/io/forgetdm/automation/EnterpriseSelfServiceService.java:202` | **PARTIAL** | Approval lacks explicit resource/outcome/decision metadata. |
| `POST /api/self-service/v2/orders/{id}/decision/reject` - `src/main/java/io/forgetdm/automation/EnterpriseSelfServiceController.java:31` | Legacy dynamic `SELF_SERVICE_ORDER_REJECTED` - `src/main/java/io/forgetdm/automation/EnterpriseSelfServiceService.java:202` | **PARTIAL** | Same limitation. |
| `POST /api/self-service/v2/orders/{id}/fulfill` - `src/main/java/io/forgetdm/automation/EnterpriseSelfServiceController.java:32` | Legacy `SELF_SERVICE_ORDER_FULFILLED` - `src/main/java/io/forgetdm/automation/EnterpriseSelfServiceService.java:223` | **PARTIAL** | Run/order identity and outcome are not structured. |
| `POST /api/self-service/v2/orders/{id}/cancel` - `src/main/java/io/forgetdm/automation/EnterpriseSelfServiceController.java:33` | None | **GAP** | Writes order status and local order event only (`EnterpriseSelfServiceService.java:234-235`). |
| `POST /api/self-service/v2/orders/{id}/comments` - `src/main/java/io/forgetdm/automation/EnterpriseSelfServiceController.java:34` | None | **GAP** | Persists a local order event but no audit-ledger event. |
| `GET /api/self-service/v2/orders/{id}/runner` - `src/main/java/io/forgetdm/automation/EnterpriseSelfServiceController.java:35` | None | **GAP** | Exports executable runner commands without export evidence. |

Module total: **20 = 0 COVERED + 16 PARTIAL + 4 GAP**.

## Business entity

| Endpoint and controller | Audit event(s) actually emitted | Status | Finding |
|---|---|---|---|
| `POST /api/business-entities` - `src/main/java/io/forgetdm/businessentity/BusinessEntityController.java:50` | Legacy `BUSINESS_ENTITY_CREATE` - `src/main/java/io/forgetdm/businessentity/BusinessEntityService.java:98` | **PARTIAL** | Name only; explicit entity id/outcome absent. |
| `POST /api/business-entities/from-dataset/{datasetId}` - `BusinessEntityController.java:55` | Legacy `BUSINESS_ENTITY_CREATE` and `BUSINESS_ENTITY_MEMBERS_SAVE` - `BusinessEntityService.java:98`, `:149` | **PARTIAL** | Two mutations are logged, but both use inferred identities/outcomes. |
| `POST /api/business-entities/{id}/datasets/{datasetId}/import` - `BusinessEntityController.java:62` | Legacy `BUSINESS_ENTITY_BLUEPRINT_ATTACH` - `BusinessEntityService.java:229` | **PARTIAL** | Entity/dataset evidence is free-form. |
| `PUT /api/business-entities/{id}` - `BusinessEntityController.java:70` | Legacy `BUSINESS_ENTITY_UPDATE` - `BusinessEntityService.java:111` | **PARTIAL** | Explicit entity id/outcome absent. |
| `PUT /api/business-entities/{id}/members` - `BusinessEntityController.java:76` | Legacy `BUSINESS_ENTITY_MEMBERS_SAVE` - `BusinessEntityService.java:149` | **PARTIAL** | Member set/count not structured. |
| `DELETE /api/business-entities/{id}` - `BusinessEntityController.java:82` | Legacy `BUSINESS_ENTITY_DELETE` - `BusinessEntityService.java:121` | **PARTIAL** | Explicit entity id/outcome absent. |
| `POST /api/business-entities/{id}/snapshots` - `BusinessEntityController.java:94` | Legacy `BUSINESS_ENTITY_SNAPSHOT_CREATE` - `BusinessEntitySnapshotService.java:137` | **PARTIAL** | Snapshot/entity identity and outcome inferred. |
| `POST /api/business-entities/snapshots/{snapshotId}/rollback` - `BusinessEntityController.java:106` | Legacy `BUSINESS_ENTITY_ROLLBACK` - `BusinessEntitySnapshotService.java:175` | **PARTIAL** | Rollback outcome/resource not explicit. |
| `POST /api/business-entities/{id}/reservations` - `BusinessEntityController.java:118` | Legacy `BUSINESS_ENTITY_RESERVED` - `BusinessEntityReservationService.java:131` | **PARTIAL** | Reservation/entity identity is free-form. |
| `POST /api/business-entities/reservations/{reservationId}/release` - `BusinessEntityController.java:130` | Legacy `BUSINESS_ENTITY_RESERVATION_RELEASED` - `BusinessEntityReservationService.java:155` | **PARTIAL** | Resource/outcome inferred. |
| `POST /api/business-entities/{id}/identities` - `BusinessEntityController.java:147` | Legacy `BUSINESS_ENTITY_IDENTITY_UPSERT` - `BusinessEntityIdentityService.java:96` | **PARTIAL** | Subject/canonical identity not structured. |
| `POST /api/business-entities/{id}/identities/resolve` - `BusinessEntityController.java:159` | None | **N/A-read-only** | Performs a crosswalk lookup and returns a match; no persisted mutation. |
| `POST /api/business-entities/{id}/identities/{subjectId}/links` - `BusinessEntityController.java:166` | None | **GAP** | Persists an identity link at `BusinessEntityIdentityService.java:102-107` without audit evidence. |
| `DELETE /api/business-entities/identities/{subjectId}` - `BusinessEntityController.java:174` | Legacy `BUSINESS_ENTITY_IDENTITY_DELETE` - `BusinessEntityIdentityService.java:151` | **PARTIAL** | Subject/canonical key is detail text. |
| `DELETE /api/business-entities/identity-links/{linkId}` - `BusinessEntityController.java:180` | Legacy `BUSINESS_ENTITY_IDENTITY_LINK_DELETE` - `BusinessEntityIdentityService.java:144` | **PARTIAL** | Link/entity identity and outcome inferred. |
| `POST /api/business-entities/{id}/sync-policies` - `BusinessEntityController.java:191` | Legacy `BUSINESS_ENTITY_SYNC_POLICY_SAVE` - `BusinessEntitySyncService.java:102` | **PARTIAL** | Policy/entity identity not structured. |
| `DELETE /api/business-entities/sync-policies/{policyId}` - `BusinessEntityController.java:203` | Legacy `BUSINESS_ENTITY_SYNC_POLICY_DELETE` - `BusinessEntitySyncService.java:111` | **PARTIAL** | Resource/outcome inferred. |
| `POST /api/business-entities/sync-policies/{policyId}/check` - `BusinessEntityController.java:209` | Legacy `BUSINESS_ENTITY_SYNC_FRESHNESS_CHECK` - `BusinessEntitySyncService.java:142` | **PARTIAL** | Persists/checks freshness evidence, but result/outcome is not structured. |
| `POST /api/business-entities/sync-policies/{policyId}/heartbeat` - `BusinessEntityController.java:219` | Legacy `BUSINESS_ENTITY_SYNC_HEARTBEAT` - `BusinessEntitySyncService.java:165` | **PARTIAL** | Updated member watermark/status is free-form. |
| `POST /api/business-entities/{id}/flows` - `BusinessEntityController.java:236` | Legacy `BUSINESS_ENTITY_FLOW_SAVE` - `BusinessEntityFlowService.java:137` | **PARTIAL** | Flow/entity identity and outcome inferred. |
| `DELETE /api/business-entities/flows/{flowId}` - `BusinessEntityController.java:248` | Legacy `BUSINESS_ENTITY_FLOW_DELETE` - `BusinessEntityFlowService.java:166` | **PARTIAL** | Same limitation. |
| `POST /api/business-entities/flows/{flowId}/validate` - `BusinessEntityController.java:259` | None | **N/A-read-only** | Validates a stored flow without changing it. |
| `POST /api/business-entities/flows/{flowId}/publish` - `BusinessEntityController.java:264` | Legacy `BUSINESS_ENTITY_FLOW_PUBLISH` - `BusinessEntityFlowService.java:158` | **PARTIAL** | Publish decision/resource/outcome not structured. |
| `POST /api/business-entities/flows/{flowId}/debug` - `BusinessEntityController.java:269` | Legacy `BUSINESS_ENTITY_FLOW_DEBUG` - `BusinessEntityFlowService.java:280` | **PARTIAL** | Debug run evidence is legacy. |
| `POST /api/business-entities/flows/{flowId}/run` - `BusinessEntityController.java:276` | Legacy `BUSINESS_ENTITY_FLOW_RUN` - `BusinessEntityFlowService.java:280` | **PARTIAL** | Physical run resource/outcome not explicit. |
| `POST /api/business-entities/{id}/issue-packages` - `BusinessEntityController.java:283` | Legacy `BUSINESS_ENTITY_ISSUE_PACKAGE_CREATE` - `BusinessEntityEnterpriseService.java:197` | **PARTIAL** | Package/entity/issue identity not structured. |
| `POST /api/business-entities/{id}/lookalike-profiles` - `BusinessEntityController.java:290` | Legacy `BUSINESS_ENTITY_LOOKALIKE_PROFILE_CREATE` - `BusinessEntityEnterpriseService.java:220` | **PARTIAL** | Profile/entity identity not structured. |
| `POST /api/business-entities/{id}/catalog/sync` - `BusinessEntityController.java:297` | Legacy `BUSINESS_ENTITY_CATALOG_SYNC` - `BusinessEntityEnterpriseService.java:251` | **PARTIAL** | Asset counts/result not structured. |
| `POST /api/business-entities/{id}/governance-requests` - `BusinessEntityController.java:302` | Legacy `BUSINESS_ENTITY_GOVERNANCE_REQUEST` - `BusinessEntityEnterpriseService.java:276` | **PARTIAL** | Request id/action in detail; explicit outcome/resource absent. |
| `POST /api/business-entities/governance-requests/{requestId}/approve` - `BusinessEntityController.java:309` | Legacy `BUSINESS_ENTITY_GOVERNANCE_APPROVED` - `BusinessEntityEnterpriseService.java:288` | **PARTIAL** | Approval/e-signature context is not structured. |
| `POST /api/business-entities/governance-requests/{requestId}/reject` - `BusinessEntityController.java:316` | Legacy `BUSINESS_ENTITY_GOVERNANCE_REJECTED` - `BusinessEntityEnterpriseService.java:296` | **PARTIAL** | Rejection outcome is inferred from action text. |
| `POST /api/business-entities/{id}/execution-plans` - `BusinessEntityController.java:323` | Legacy `BUSINESS_ENTITY_EXECUTION_PLAN_CREATE` - `BusinessEntityEnterpriseService.java:344` | **PARTIAL** | Plan/entity identity not structured. |
| `POST /api/business-entities/execution-plans/{planId}/launch` - `BusinessEntityController.java:330` | Legacy `BUSINESS_ENTITY_MICRO_DB_PLAN_LAUNCH`, `...FANOUT_LAUNCH`, `...EXECUTION_PLAN_LAUNCH`, and run record events depending path - `BusinessEntityEnterpriseService.java:398`, `:585`, `:663`, `:717`, `:894` | **PARTIAL** | Broad lifecycle exists, but events are legacy and failure/decision metadata is not an explicit contract. |
| `POST /api/business-entities/{id}/operational-packages` - `BusinessEntityController.java:337` | Legacy `BUSINESS_ENTITY_OPERATIONAL_PACKAGE_CREATE` - `BusinessEntityEnterpriseService.java:435` | **PARTIAL** | Package/entity identity not structured. |
| `POST /api/business-entities/operational-packages/{packageId}/versions` - `BusinessEntityController.java:354` | Legacy `BUSINESS_ENTITY_PACKAGE_VERSION_CREATE` - `BusinessEntityEnterpriseService.java:510` | **PARTIAL** | Version hash/retention/resource not structured. |
| `POST /api/business-entities/operational-packages/{packageId}/promotions` - `BusinessEntityController.java:361` | Legacy `BUSINESS_ENTITY_PACKAGE_PROMOTION` - `BusinessEntityEnterpriseService.java:548` | **PARTIAL** | Promotion decision/environments/outcome not structured. |
| `POST /api/business-entities/{id}/capsules/materialize` - `BusinessEntityController.java:375` | Legacy `BUSINESS_ENTITY_CAPSULE_MATERIALIZE` - `BusinessEntityCapsuleService.java:370` | **PARTIAL** | Capsule/entity/business-key identity not structured. |
| `POST /api/business-entities/capsules/{instanceId}/versions/{versionNo}/restore` - `BusinessEntityController.java:394` | Legacy `BUSINESS_ENTITY_CAPSULE_RESTORE` - `BusinessEntityCapsuleService.java:437` | **PARTIAL** | Version/instance/outcome inferred. |
| `POST /api/business-entities/capsules/{instanceId}/provision` - `BusinessEntityController.java:400` | Legacy `BUSINESS_ENTITY_CAPSULE_PROVISION` - `BusinessEntityCapsuleService.java:501` | **PARTIAL** | Target/result/resource not structured. |
| `POST /api/business-entities/capsules/{instanceId}/retire` - `BusinessEntityController.java:407` | Legacy `BUSINESS_ENTITY_CAPSULE_RETIRE` - `BusinessEntityCapsuleService.java:515` | **PARTIAL** | Resource/outcome inferred. |
| `POST /api/business-entities/capsules/{instanceId}/access-grants` - `BusinessEntityController.java:412` | Legacy `BUSINESS_ENTITY_CAPSULE_ACCESS_GRANT` - `BusinessEntityCapsuleService.java:537` | **PARTIAL** | Grant scope/grantee/resource not structured. |
| `POST /api/business-entities/capsule-access-grants/{grantId}/revoke` - `BusinessEntityController.java:419` | Legacy `BUSINESS_ENTITY_CAPSULE_ACCESS_REVOKE` - `BusinessEntityCapsuleService.java:552` | **PARTIAL** | Grant/instance identity and outcome inferred. |

Module total: **42 = 0 COVERED + 39 PARTIAL + 1 GAP + 2 N/A-read-only**.

## Copybook utilities

| Endpoint and controller | Audit event(s) actually emitted | Status | Finding |
|---|---|---|---|
| `POST /api/copybook/parse` - `src/main/java/io/forgetdm/copybook/CopybookController.java:38` | None | **N/A-read-only** | Parses supplied copybook text only. |
| `POST /api/copybook/decode` - `CopybookController.java:60` | None | **N/A-read-only** | Decodes supplied bytes only. |
| `POST /api/copybook/decode-file` - `CopybookController.java:75` | None | **N/A-read-only** | Reads an uploaded file for decode preview; does not persist/deliver it. |
| `POST /api/copybook/mask-preview` - `CopybookController.java:108` | None | **N/A-read-only** | In-memory preview only. |

Module total: **4 = 4 N/A-read-only**.

## Dataset and DataScope versioning

| Endpoint and controller | Audit event(s) actually emitted | Status | Finding |
|---|---|---|---|
| `POST /api/datasets` - `src/main/java/io/forgetdm/dataset/DataSetController.java:26` | Structured `DATASET_CREATED` - `src/main/java/io/forgetdm/dataset/DataSetService.java:127` | **COVERED** | Actor, dataset id/name, outcome, and safe source metadata are explicit. |
| `PUT /api/datasets/{id}` - `DataSetController.java:31` | Structured `DATASET_UPDATED` - `DataSetService.java:154` | **COVERED** | Explicit dataset identity/outcome and changed design metadata. |
| `PUT /api/datasets/{id}/policy` - `DataSetController.java:36` | Structured `DATASET_POLICY_UPDATED` - `DataSetService.java:165` | **COVERED** | Dataset/policy identity and outcome are explicit. |
| `DELETE /api/datasets/{id}` - `DataSetController.java:44` | Structured `DATASET_DELETED` - `DataSetService.java:228` | **COVERED** | Explicit deleted dataset identity/outcome. |
| `PUT /api/datasets/{id}/profiles` - `DataSetController.java:56` | Legacy `DATASET_PROFILES_SAVED` - `DataSetService.java:258` | **PARTIAL** | Dataset/count are detail text; actor/outcome/resource are inferred. |
| `POST /api/datasets/{id}/profiles` - `DataSetController.java:62` | None | **GAP** | Saves/replaces one table profile without an audit event. |
| `DELETE /api/datasets/{id}/profiles/{tableName}` - `DataSetController.java:67` | None | **GAP** | Deletes a profile without audit evidence. |
| `PUT /api/datasets/{id}/overrides` - `DataSetController.java:80` | Legacy `DATASET_OVERRIDES_SAVED` - `DataSetService.java:298` | **PARTIAL** | Dataset/count only; override identities/outcome not explicit. |
| `POST /api/datasets/{id}/overrides` - `DataSetController.java:86` | None | **GAP** | Saves/replaces one column override without audit evidence. |
| `DELETE /api/datasets/overrides/{overrideId}` - `DataSetController.java:91` | None | **GAP** | Deletes an override without audit evidence. |
| `PUT /api/datasets/{id}/custom-pks` - `DataSetController.java:103` | None | **GAP** | Replaces tool-level PK definitions without audit evidence. |
| `POST /api/datasets/{id}/custom-pks` - `DataSetController.java:110` | None | **GAP** | Saves/replaces one tool-level PK without audit evidence. |
| `DELETE /api/datasets/custom-pks/{pkId}` - `DataSetController.java:115` | None | **GAP** | Deletes a tool-level PK without audit evidence. |
| `POST /api/datasets/{id}/user-rels` - `DataSetController.java:126` | Legacy `USER_REL_CREATED` - `DataSetService.java:368` | **PARTIAL** | Dataset and parent/child are detail text; relation id/outcome not explicit. |
| `PUT /api/datasets/user-rels/{relId}` - `DataSetController.java:132` | None | **GAP** | Updates a tool-level relationship without audit evidence. |
| `DELETE /api/datasets/user-rels/{relId}` - `DataSetController.java:138` | None | **GAP** | Deletes a relationship and traversal rules without audit evidence. |
| `PUT /api/datasets/{id}/traversal-rules` - `DataSetController.java:150` | None | **GAP** | Replaces relationship traversal decisions without audit evidence. |
| `POST /api/datasets/{id}/preview` - `DataSetController.java:188` | None | **N/A-read-only** | Computes a dry subset plan only. |
| `POST /api/datasets/{id}/versions` - `src/main/java/io/forgetdm/dataset/DataScopeVersionController.java:24` | Legacy `DATASCOPE_VERSION_SAVED` - `src/main/java/io/forgetdm/dataset/DataScopeVersionService.java:114` | **PARTIAL** | Dataset/version identity is detail text; outcome not explicit. |
| `POST /api/datasets/versions/{versionId}/restore` - `DataScopeVersionController.java:43` | Legacy `DATASCOPE_VERSION_RESTORED` - `DataScopeVersionService.java:303` | **PARTIAL** | Version/dataset identity and restore outcome are inferred. |

Module total: **20 = 4 COVERED + 5 PARTIAL + 10 GAP + 1 N/A-read-only**.

## Data sources

| Endpoint and controller | Audit event(s) actually emitted | Status | Finding |
|---|---|---|---|
| `POST /api/datasources` - `src/main/java/io/forgetdm/datasource/DataSourceController.java:25` | Structured `DATASOURCE_CREATED` - `src/main/java/io/forgetdm/datasource/DataSourceService.java:112` | **COVERED** | Actor, id/name, outcome, engine/role metadata, and safe JDBC detail are explicit. |
| `PUT /api/datasources/{id}` - `DataSourceController.java:26` | Structured `DATASOURCE_UPDATED` - `DataSourceService.java:151` | **COVERED** | Explicit resource/outcome and safe metadata. |
| `DELETE /api/datasources/{id}` - `DataSourceController.java:28` | Structured `DATASOURCE_DELETED` - `DataSourceService.java:195` | **COVERED** | Explicit deleted source identity/outcome. |
| `POST /api/datasources/{id}/test` - `DataSourceController.java:29` | Structured `DATASOURCE_TESTED` with SUCCESS/FAILURE - `DataSourceService.java:209` | **COVERED** | Both probe outcomes are recorded with source identity and sanitized URL. |
| `POST /api/datasources/test-connection` - `DataSourceController.java:30` | None | **N/A-read-only** | Probes an unsaved transient definition and persists nothing. |

Module total: **5 = 4 COVERED + 1 N/A-read-only**.

## Discovery

| Endpoint and controller | Audit event(s) actually emitted | Status | Finding |
|---|---|---|---|
| `POST /api/discovery/scan/{dataSourceId}` - `src/main/java/io/forgetdm/discovery/DiscoveryController.java:17` | Legacy `DISCOVERY_SCAN` - `src/main/java/io/forgetdm/discovery/DiscoveryService.java:189` | **PARTIAL** | Success-only summary; actor/resource/outcome are inferred and failure is not audited. |
| `POST /api/discovery/scan-jobs/{dataSourceId}` - `DiscoveryController.java:27` | Structured `DISCOVERY_JOB_QUEUED`, terminal `...COMPLETED` / `...CANCELLED` / `...FAILED` - `src/main/java/io/forgetdm/discovery/DiscoveryJobService.java:66`, `:112`, `:116`, `:124`, `:128` | **COVERED** | Owner, run id/schema, outcomes, scope metadata, and lifecycle timestamps are explicit. |
| `POST /api/discovery/scan-jobs/{jobId}/cancel` - `DiscoveryController.java:47` | Structured `DISCOVERY_JOB_CANCEL_REQUESTED` and terminal cancellation - `DiscoveryJobService.java:100`, `:116`, `:124` | **COVERED** | Explicit run identity, actor, and safe terminal outcome. |
| `POST /api/discovery/approve-all/{dataSourceId}` - `DiscoveryController.java:66` | Legacy `CLASSIFICATIONS_APPROVED` - `DiscoveryService.java:548` | **PARTIAL** | Source/schema/table/type/count are free-form; explicit outcome/resource absent. |
| `POST /api/discovery/reject-all/{dataSourceId}` - `DiscoveryController.java:74` | Legacy `CLASSIFICATIONS_REJECTED` - `DiscoveryService.java:569` | **PARTIAL** | Same limitation. |
| `POST /api/discovery/manual/{dataSourceId}` - `DiscoveryController.java:90` | Legacy `CLASSIFICATION_MANUAL` - `DiscoveryService.java:667` | **PARTIAL** | Classification identity/outcome not structured. |
| `PATCH /api/discovery/classifications/{id}` - `DiscoveryController.java:103` | Conditional legacy `CLASSIFICATION_RULE_CHANGED` - `DiscoveryService.java:353` | **PARTIAL** | Status-only changes emit no event; function changes use legacy detail. |
| `POST /api/discovery/classifications/bulk` - `DiscoveryController.java:114` | Legacy `CLASSIFICATIONS_BULK_UPDATED` - `DiscoveryService.java:245` | **PARTIAL** | IDs/status/count are not structured. |
| `POST /api/discovery/generate-policy/{dataSourceId}` - `DiscoveryController.java:119` | Legacy `POLICY_GENERATED` (and optional `POLICY_RI_RULE_SKIPPED`) - `DiscoveryService.java:410`, `:385` | **PARTIAL** | Generated policy id/source/outcome are not explicit. |
| `POST /api/discovery/patterns` - `src/main/java/io/forgetdm/discovery/PiiPatternController.java:24` | Legacy `PII_PATTERN_CREATED` - `src/main/java/io/forgetdm/discovery/PiiPatternService.java:72` | **PARTIAL** | Pattern id/visibility/outcome not structured. |
| `POST /api/discovery/patterns/test` - `PiiPatternController.java:29` | None | **N/A-read-only** | Tests a supplied regex/sample only. |
| `DELETE /api/discovery/patterns/{id}` - `PiiPatternController.java:34` | Legacy `PII_PATTERN_DELETED` - `PiiPatternService.java:94` | **PARTIAL** | Pattern id is detail text; outcome inferred. |

Module total: **12 = 2 COVERED + 9 PARTIAL + 1 N/A-read-only**.

## Mainframe

| Endpoint and controller | Audit event(s) actually emitted | Status | Finding |
|---|---|---|---|
| `POST /api/mainframe/generate-file` - `src/main/java/io/forgetdm/mainframe/MainframeController.java:43` | Structured `MAINFRAME_FILE_DELIVERED` or `MAINFRAME_FILE_EXPORTED` - `src/main/java/io/forgetdm/mainframe/MainframeGenService.java:109`, `:113` | **COVERED** | Copybook identity, actor, outcome, row/target metadata, and timestamp are explicit. |
| `POST /api/mainframe/connections` - `MainframeController.java:57` | None | **GAP** | Creates a connection containing operational settings without audit evidence. |
| `PUT /api/mainframe/connections/{id}` - `MainframeController.java:74` | None | **GAP** | Updates a connection without audit evidence. |
| `DELETE /api/mainframe/connections/{id}` - `MainframeController.java:103` | None | **GAP** | Deletes a connection without audit evidence. |
| `POST /api/mainframe/connections/{id}/test` - `MainframeController.java:109` | None | **N/A-read-only** | Connectivity probe only; no persisted state. |
| `POST /api/mainframe/copybooks` - `MainframeController.java:153` | None | **GAP** | Creates governed copybook source/compiled fields without audit evidence. |
| `PUT /api/mainframe/copybooks/{id}` - `MainframeController.java:164` | None | **GAP** | Updates copybook source/fields without audit evidence. |
| `DELETE /api/mainframe/copybooks/{id}` - `MainframeController.java:183` | None | **GAP** | Deletes copybook and masks without audit evidence. |
| `PUT /api/mainframe/copybooks/{id}/masks` - `MainframeController.java:204` | None | **GAP** | Replaces field masking rules without audit evidence. |
| `POST /api/mainframe/jobs` - `MainframeController.java:243` | Structured `MAINFRAME_JOB_QUEUED`, `...STARTED`, file events, and terminal event - `src/main/java/io/forgetdm/mainframe/MainframeMaskingService.java:55`, `:136`, `:236`, `:265` | **COVERED** | Job/file identity, actor, explicit lifecycle outcome, and counts are present. |
| `POST /api/mainframe/jobs/{id}/cancel` - `MainframeController.java:281` | Structured `MAINFRAME_JOB_CANCEL_REQUESTED` and terminal cancellation - `MainframeMaskingService.java:71`, `:265` | **COVERED** | Explicit job identity/actor/outcome. |
| `POST /api/mainframe/jobs/{id}/retry` - `MainframeController.java:287` | Structured `MAINFRAME_JOB_RETRIED` plus new attempt lifecycle - `MainframeMaskingService.java:114`, `:55`, `:136`, `:265` | **COVERED** | Previous/new attempt and retained-file metadata are explicit. |

Module total: **12 = 4 COVERED + 7 GAP + 1 N/A-read-only**.

## Mapping and workflows

| Endpoint and controller | Audit event(s) actually emitted | Status | Finding |
|---|---|---|---|
| `POST /api/mappings` - `src/main/java/io/forgetdm/mapping/MappingController.java:42` | Structured `MAPPING_CREATED` or `MAPPING_UPDATED` - `src/main/java/io/forgetdm/mapping/MappingService.java:84` | **COVERED** | Actor, mapping id/name, outcome, version/spec metadata are explicit. |
| `DELETE /api/mappings/{id}` - `MappingController.java:44` | Structured `MAPPING_DELETED` - `MappingService.java:94` | **COVERED** | Explicit mapping identity/outcome. |
| `POST /api/mappings/{id}/versions/{versionId}/restore` - `MappingController.java:48` | Legacy `MAPPING_VERSION_RESTORED` - `MappingService.java:113` | **PARTIAL** | Mapping/version identity and outcome are not structured. |
| `POST /api/mappings/validate` - `MappingController.java:51` | None | **N/A-read-only** | Validates a supplied mapping spec only. |
| `POST /api/mappings/assets` - `MappingController.java:57` | Structured `MAPPING_FILE_UPLOADED` - `src/main/java/io/forgetdm/mapping/MappingFileService.java:88` | **COVERED** | Asset id/name, actor, outcome, size/type/hash metadata are explicit. |
| `DELETE /api/mappings/assets/{id}` - `MappingController.java:71` | Legacy `MAPPING_FILE_DELETED` - `MappingFileService.java:174` | **PARTIAL** | Asset identity/outcome not structured. |
| `POST /api/mappings/{id}/runs` - `MappingController.java:73` | Structured `MAPPING_RUN_QUEUED`, `...STARTED`, `...COMPLETED` / `...FAILED` / `...CANCELED` - `src/main/java/io/forgetdm/mapping/MappingExecutionService.java:85`, `:258`, `:271`, `:276`, `:281` | **COVERED** | Full run lifecycle has explicit actor, resource, outcome, and result/error detail. |
| `POST /api/mappings/runs/{id}/cancel` - `MappingController.java:81` | Structured `MAPPING_RUN_CANCEL_REQUESTED` and terminal cancellation - `MappingExecutionService.java:123`, `:276` | **COVERED** | Explicit run identity/actor/outcome. |
| `POST /api/mappings/runs/{id}/retry` - `MappingController.java:83` | Structured `MAPPING_RUN_RETRIED` plus new run lifecycle - `MappingExecutionService.java:138`, `:85`, `:258`, `:271`, `:281` | **COVERED** | Previous/new run relation and outcomes are explicit. |
| `GET /api/mappings/runs/{id}/download` - `MappingController.java:85` | Structured `MAPPING_OUTPUT_EXPORTED` - `MappingController.java:88` | **COVERED** | Run identity, actor, outcome, receiver/row/hash metadata are explicit. |
| `POST /api/mappings/preview` - `MappingController.java:96` | None | **N/A-read-only** | Query preview only. |
| `POST /api/mappings/preview-spec` - `MappingController.java:102` | None | **N/A-read-only** | Compiles/previews an unsaved spec only. |
| `POST /api/mappings/load` - `MappingController.java:105` | Legacy `MAPPING_LOADED` - `MappingService.java:226` | **PARTIAL** | External target mutation is logged only after success; source/target/outcome are not structured. |
| `POST /api/mappings/load-multi` - `MappingController.java:112` | Legacy `MAPPING_MULTI_LOADED` - `MappingService.java:274` | **PARTIAL** | Same success-only legacy limitation. |
| `POST /api/mappings/federated` - `MappingController.java:121` | Legacy `MAPPING_FEDERATED` - `MappingService.java:437` | **PARTIAL** | Material federated execution result is legacy and failure is not covered. |
| `POST /api/mappings/workflows` - `src/main/java/io/forgetdm/mapping/WorkflowController.java:22` | Legacy `WORKFLOW_SAVED` - `src/main/java/io/forgetdm/mapping/WorkflowService.java:68` | **PARTIAL** | Workflow id/version/outcome not structured. |
| `DELETE /api/mappings/workflows/{id}` - `WorkflowController.java:24` | Legacy `WORKFLOW_DELETED` - `WorkflowService.java:76` | **PARTIAL** | Resource/outcome inferred. |
| `POST /api/mappings/workflows/{id}/run` - `WorkflowController.java:29` | Legacy `WORKFLOW_STARTED`, then `WORKFLOW_COMPLETED` / `WORKFLOW_FAILED` - `WorkflowService.java:95`, `:131` | **PARTIAL** | Lifecycle exists, but actor/resource/outcomes are not explicit fields. |

Module total: **18 = 7 COVERED + 8 PARTIAL + 3 N/A-read-only**.

## Masking policy and scripts

| Endpoint and controller | Audit event(s) actually emitted | Status | Finding |
|---|---|---|---|
| `POST /api/policies` - `src/main/java/io/forgetdm/policy/PolicyController.java:48` | Structured `POLICY_CREATED` - `src/main/java/io/forgetdm/policy/PolicyController.java:58` | **COVERED** | Actor, policy id/name, outcome, and ownership/scope metadata are explicit. |
| `PUT /api/policies/{id}` - `PolicyController.java:65` | Structured `POLICY_UPDATED` - `PolicyController.java:89` | **COVERED** | Explicit policy identity/outcome and ownership metadata. |
| `DELETE /api/policies/{id}` - `PolicyController.java:94` | Structured `POLICY_DELETED` - `PolicyController.java:100` | **COVERED** | Explicit deleted policy identity/outcome. |
| `POST /api/policies/{id}/rules` - `PolicyController.java:118` | Structured `POLICY_RULE_CREATED` - `PolicyController.java:129` | **COVERED** | Rule id plus parent policy identity/outcome are explicit. |
| `PATCH /api/policies/rules/{ruleId}` - `PolicyController.java:136` | Structured `POLICY_RULE_UPDATED` - `PolicyController.java:152` | **COVERED** | Rule/policy identity, actor, outcome, and changed function metadata are explicit. |
| `DELETE /api/policies/rules/{ruleId}` - `PolicyController.java:160` | Structured `POLICY_RULE_DELETED` - `PolicyController.java:165` | **COVERED** | Explicit rule/policy identity/outcome. |
| `POST /api/policies/preview` - `PolicyController.java:184` | None | **N/A-read-only** | In-memory masking preview only. |
| `POST /api/policies/scripts` - `src/main/java/io/forgetdm/policy/MaskingScriptController.java:21` | Legacy `MASKING_SCRIPT_SAVED` - `src/main/java/io/forgetdm/policy/MaskingScriptService.java:86` | **PARTIAL** | Script id/version/outcome not structured. |
| `DELETE /api/policies/scripts/{id}` - `MaskingScriptController.java:24` | Legacy `MASKING_SCRIPT_DELETED` - `MaskingScriptService.java:94` | **PARTIAL** | Script identity/outcome inferred. |

Module total: **9 = 6 COVERED + 2 PARTIAL + 1 N/A-read-only**.

## Provisioning and synthetic

| Endpoint and controller | Audit event(s) actually emitted | Status | Finding |
|---|---|---|---|
| `POST /api/datascope/saved-jobs` - `src/main/java/io/forgetdm/provision/DataScopeJobController.java:22` | Legacy `DATASCOPE_JOB_SAVED` - `src/main/java/io/forgetdm/provision/DataScopeJobService.java:71` | **PARTIAL** | Saved-job id/name are detail text; outcome/resource not explicit. |
| `PUT /api/datascope/saved-jobs/{id}` - `DataScopeJobController.java:23` | Legacy `DATASCOPE_JOB_UPDATED` - `DataScopeJobService.java:123` | **PARTIAL** | Same limitation. |
| `DELETE /api/datascope/saved-jobs/{id}` - `DataScopeJobController.java:28` | Legacy `DATASCOPE_JOB_DELETED` - `DataScopeJobService.java:130` | **PARTIAL** | Same limitation. |
| `POST /api/datascope/saved-jobs/{id}/run` - `DataScopeJobController.java:32` | Legacy `DATASCOPE_JOB_RUN` - `DataScopeJobService.java:172`; downstream provision events may also occur | **PARTIAL** | Saved-job launch is legacy even if the resulting provision run is structured. |
| `PUT /api/datascope/saved-jobs/{id}/schedule` - `DataScopeJobController.java:34` | Legacy `DATASCOPE_JOB_SCHEDULE_SET` - `DataScopeJobService.java:255` | **PARTIAL** | Schedule/identity/outcome not structured. |
| `POST /api/datascope/saved-jobs/schedule/preview` - `DataScopeJobController.java:40` | None | **N/A-read-only** | Computes upcoming schedule times only. |
| `POST /api/datasets/{id}/preview-mask` - `src/main/java/io/forgetdm/provision/DataScopePreviewController.java:26` | None | **N/A-read-only** | Reads and masks a preview sample without target mutation. |
| `POST /api/jobs` - `src/main/java/io/forgetdm/provision/ProvisionController.java:22` | Structured `PROVISION_APPROVAL_REQUESTED` or `PROVISION_JOB_QUEUED`, then `...STARTED` and terminal event - `src/main/java/io/forgetdm/provision/ProvisioningService.java:159`, `:166`, `:445`, `:490` | **COVERED** | Actor, job identity, explicit lifecycle outcome, type/status/count metadata are present. |
| `POST /api/jobs/{id}/cancel` - `ProvisionController.java:23` | Structured `PROVISION_JOB_CANCEL_REQUESTED` and terminal event - `ProvisioningService.java:295`, `:313`, `:490` | **COVERED** | Explicit actor/job/outcome. |
| `POST /api/jobs/{id}/retry` - `ProvisionController.java:24` | Structured `PROVISION_JOB_RETRIED` plus new job lifecycle - `ProvisioningService.java:334`, `:166`, `:445`, `:490` | **COVERED** | Previous/new job relation and outcomes are explicit. |
| `POST /api/jobs/{id}/approval/approve` - `ProvisionController.java:27` | Structured `PROVISION_APPROVED` plus execution lifecycle - `ProvisioningService.java:241`, `:445`, `:490` | **COVERED** | Reviewer, decision comment, job identity, and outcome are explicit. |
| `POST /api/jobs/{id}/approval/reject` - `ProvisionController.java:32` | Structured `PROVISION_REJECTED` - `ProvisioningService.java:281` | **COVERED** | Reviewer, decision, comment, job identity, and explicit SUCCESS of rejection action. |
| `DELETE /api/jobs/{id}` - `ProvisionController.java:37` | Legacy `JOB_DELETED` - `ProvisioningService.java:356` | **PARTIAL** | Job identity/outcome not structured. |
| `GET /api/jobs/{id}/sample` - `ProvisionController.java:47` | None | **GAP** | Exports target sample data (`ProvisioningService.java:371`) without read/export evidence. |
| `POST /api/synthetic/profile` - `src/main/java/io/forgetdm/provision/SyntheticController.java:29` | Legacy `SYNTHETIC_PROFILED` - `src/main/java/io/forgetdm/provision/SyntheticProfileService.java:89` | **N/A-read-only** | Reads schema/sample distributions only; legacy telemetry exists but no business mutation. |
| `POST /api/synthetic/generate` - `SyntheticController.java:43` | None | **GAP** | Synchronous generation mutates target/files through `SyntheticGenService.java:265` but bypasses structured job/audit lifecycle. |
| `POST /api/synthetic/generate/start` - `SyntheticController.java:48` | Structured `SYNTHETIC_JOB_QUEUED`, `...STARTED`, and terminal `...COMPLETED` / `...FAILED` / `...CANCELLED` - `src/main/java/io/forgetdm/provision/SyntheticGenService.java:557`, `:568`, `:1573` | **COVERED** | Actor, run/dataset identity, explicit outcomes, plan hash, target/row metadata, and timestamps are present. |
| `POST /api/synthetic/plan-summary` - `SyntheticController.java:53` | None | **N/A-read-only** | Validates/summarizes a plan only. |
| `POST /api/synthetic/jobs/{id}/cancel` - `SyntheticController.java:68` | Structured `SYNTHETIC_JOB_CANCEL_REQUESTED` and terminal cancellation - `SyntheticGenService.java:663`, `:665`, `:692`, `:1573` | **COVERED** | Explicit actor/run/dataset/outcome. |
| `POST /api/synthetic/jobs/{jobId}/partitions/{partitionId}/cancel` - `SyntheticController.java:73` | Legacy `SYNTHETIC_PARTITION_CANCELLED` - `SyntheticGenService.java:718` | **PARTIAL** | Run/partition identity and outcome are detail inference. |
| `POST /api/synthetic/jobs/{jobId}/partitions/{partitionId}/retry` - `SyntheticController.java:78` | Legacy `SYNTHETIC_PARTITION_RETRIED` - `SyntheticGenService.java:740` | **PARTIAL** | Same limitation. |
| `POST /api/synthetic/saved-jobs` - `SyntheticController.java:88` | Structured `SYNTHETIC_JOB_SAVED` - `SyntheticGenService.java:773` | **COVERED** | Saved-job id/name, actor, outcome, and planned-row metadata are explicit. |
| `PUT /api/synthetic/saved-jobs/{id}` - `SyntheticController.java:98` | Structured `SYNTHETIC_JOB_UPDATED` - `SyntheticGenService.java:795` | **COVERED** | Explicit identity/outcome and approval reset metadata. |
| `DELETE /api/synthetic/saved-jobs/{id}` - `SyntheticController.java:103` | Structured `SYNTHETIC_JOB_DELETED` - `SyntheticGenService.java:804` | **COVERED** | Explicit saved-job identity/actor/outcome. |
| `POST /api/synthetic/saved-jobs/{id}/run` - `SyntheticController.java:108` | Structured generated-run lifecycle - `SyntheticGenService.java:557`, `:568`, `:1573`; legacy endpoint event `SYNTHETIC_JOB_RUN` - `:830` | **PARTIAL** | Run is structured and carries savedJob metadata, but the saved-job launch decision itself remains legacy. |
| `POST /api/synthetic/saved-jobs/{id}/export` - `SyntheticController.java:113` | Structured `SYNTHETIC_JOB_RUNNER_EXPORTED` - `SyntheticGenService.java:903` | **COVERED** | Saved-job identity, actor, outcome, and script format are explicit. |
| `POST /api/synthetic/saved-jobs/{id}/approval/request` - `SyntheticController.java:118` | Structured `SYNTHETIC_JOB_APPROVAL_REQUESTED` - `SyntheticGenService.java:857` | **COVERED** | Job identity, actor, decision, comment, and outcome are explicit. |
| `POST /api/synthetic/saved-jobs/{id}/approval/approve` - `SyntheticController.java:124` | Structured `SYNTHETIC_JOB_APPROVED` - `SyntheticGenService.java:877` | **COVERED** | Independent reviewer, decision/comment, resource, and outcome are explicit. |
| `POST /api/synthetic/saved-jobs/{id}/approval/reject` - `SyntheticController.java:130` | Structured `SYNTHETIC_JOB_REJECTED` - `SyntheticGenService.java:893` | **COVERED** | Reviewer, decision/comment, resource, and explicit action outcome are present. |
| `POST /api/synthetic/preview` - `SyntheticController.java:136` | None | **N/A-read-only** | Generates in-memory sample values only. |
| `POST /api/synthetic/value-lists` - `src/main/java/io/forgetdm/provision/ValueListController.java:21` | Legacy `VALUE_LIST_SAVED` - `src/main/java/io/forgetdm/provision/ValueListService.java:84` | **PARTIAL** | List id/visibility/outcome not structured. |
| `DELETE /api/synthetic/value-lists/{id}` - `ValueListController.java:24` | Legacy `VALUE_LIST_DELETED` - `ValueListService.java:93` | **PARTIAL** | Resource/outcome inferred. |
| `POST /api/synthetic/value-lists/import` - `ValueListController.java:29` | Legacy `VALUE_LIST_SAVED` through save - `ValueListService.java:84` | **PARTIAL** | Import source/list identity/outcome not structured. |

Module total: **33 = 14 COVERED + 12 PARTIAL + 2 GAP + 5 N/A-read-only**.

## Query, subset, reservations, and RI

| Endpoint and controller | Audit event(s) actually emitted | Status | Finding |
|---|---|---|---|
| `POST /api/query/run` - `src/main/java/io/forgetdm/query/QueryController.java:20` | Legacy `QUERY_RUN` - `src/main/java/io/forgetdm/query/QueryService.java:82` | **N/A-read-only** | Service enforces read-only query semantics; telemetry is legacy but no business mutation is in scope. |
| `POST /api/subset/plan` - `src/main/java/io/forgetdm/subset/SubsetController.java:16` | None | **N/A-read-only** | Computes a subset closure only. |
| `POST /api/reservations/find-and-reserve` - `src/main/java/io/forgetdm/reservation/ReservationController.java:16` | Legacy `DATA_RESERVED` - `src/main/java/io/forgetdm/reservation/ReservationService.java:87` | **PARTIAL** | Reservation/table/count/expiry are free-form; explicit resource/outcome absent. |
| `POST /api/reservations/{id}/release` - `ReservationController.java:28` | Legacy `DATA_RELEASED` - `ReservationService.java:105` | **PARTIAL** | Reservation identity/outcome inferred. |
| `POST /api/ri/keys` - `src/main/java/io/forgetdm/ri/RiController.java:29` | Legacy `RI_PK_CREATED` - `src/main/java/io/forgetdm/ri/RiRegistryService.java:68` | **PARTIAL** | Key id/source/table/outcome not structured. |
| `PUT /api/ri/keys/{id}` - `RiController.java:34` | Legacy `RI_PK_UPDATED` - `RiRegistryService.java:82` | **PARTIAL** | Same limitation. |
| `DELETE /api/ri/keys/{id}` - `RiController.java:39` | Legacy `RI_PK_DELETED` - `RiRegistryService.java:89` | **PARTIAL** | Resource/outcome inferred. |
| `POST /api/ri/relationships` - `RiController.java:50` | Legacy `RI_REL_CREATED` - `RiRegistryService.java:120` | **PARTIAL** | Relationship/source/table identity not structured. |
| `PUT /api/ri/relationships/{id}` - `RiController.java:55` | Legacy `RI_REL_UPDATED` - `RiRegistryService.java:139` | **PARTIAL** | Same limitation. |
| `DELETE /api/ri/relationships/{id}` - `RiController.java:60` | Legacy `RI_REL_DELETED` - `RiRegistryService.java:146` | **PARTIAL** | Resource/outcome inferred. |

Module total: **10 = 8 PARTIAL + 2 N/A-read-only**.

## Security and authentication

| Endpoint and controller | Audit event(s) actually emitted | Status | Finding |
|---|---|---|---|
| `POST /api/auth/login` - `src/main/java/io/forgetdm/security/AuthController.java:25` | Legacy `LOGIN_FAILED` or `LOGIN_SUCCESS` - `src/main/java/io/forgetdm/security/AccessControlService.java:168`, `:179` | **PARTIAL** | Both outcomes exist, but session/auth resource and explicit outcome are not structured. |
| `POST /api/auth/logout` - `AuthController.java:39` | Legacy `LOGOUT` - `AccessControlService.java:186` | **PARTIAL** | No explicit session resource/outcome. |
| `POST /api/auth/tokens` - `src/main/java/io/forgetdm/security/ApiTokenController.java:25` | Structured `API_TOKEN_CREATED` - `AccessControlService.java:123` | **COVERED** | Actor, token id/name, outcome, expiry/permission metadata, and timestamp are explicit; secret is not logged. |
| `DELETE /api/auth/tokens/{id}` - `ApiTokenController.java:31` | Structured `API_TOKEN_REVOKED` - `AccessControlService.java:144` | **COVERED** | Explicit token identity/actor/outcome without exposing secret material. |
| `POST /api/security/users` - `src/main/java/io/forgetdm/security/SecurityAdminController.java:42` | Structured `SECURITY_USER_CREATED` - `AccessControlService.java:223` | **COVERED** | Actor, user id/name, outcome, status/group metadata are explicit. |
| `PUT /api/security/users/{id}` - `SecurityAdminController.java:47` | Structured `SECURITY_USER_UPDATED` - `AccessControlService.java:243` | **COVERED** | Explicit user identity/outcome and safe authorization metadata. |
| `DELETE /api/security/users/{id}` - `SecurityAdminController.java:52` | Structured `SECURITY_USER_DELETED` - `AccessControlService.java:256` | **COVERED** | Explicit deleted user identity/outcome. |
| `POST /api/security/groups` - `SecurityAdminController.java:62` | Structured `SECURITY_GROUP_CREATED` - `AccessControlService.java:265` | **COVERED** | Actor, group id/name, outcome, role metadata are explicit. |
| `PUT /api/security/groups/{id}` - `SecurityAdminController.java:67` | Structured `SECURITY_GROUP_UPDATED` - `AccessControlService.java:279` | **COVERED** | Explicit group identity/outcome and membership/role metadata. |
| `DELETE /api/security/groups/{id}` - `SecurityAdminController.java:72` | Structured `SECURITY_GROUP_DELETED` - `AccessControlService.java:289` | **COVERED** | Explicit deleted group identity/outcome. |

Module total: **10 = 8 COVERED + 2 PARTIAL**.

## Unstructured masking

| Endpoint and controller | Audit event(s) actually emitted | Status | Finding |
|---|---|---|---|
| `POST /api/unstructured/profiles` - `src/main/java/io/forgetdm/unstructured/UnstructuredMaskingController.java:27` | Structured `UNSTRUCTURED_PROFILE_CREATED` or `...UPDATED` - `src/main/java/io/forgetdm/unstructured/UnstructuredMaskingService.java:112` | **COVERED** | Actor, profile id/name, explicit outcome, format/rule-count metadata are present. |
| `DELETE /api/unstructured/profiles/{id}` - `UnstructuredMaskingController.java:28` | Legacy `UNSTRUCTURED_PROFILE_DELETED` - `UnstructuredMaskingService.java:120` | **PARTIAL** | Profile id/outcome not structured. |
| `POST /api/unstructured/preview` - `UnstructuredMaskingController.java:31` | None | **N/A-read-only** | In-memory rule preview only. |
| `POST /api/unstructured/jobs` - `UnstructuredMaskingController.java:35` | Structured `UNSTRUCTURED_JOB_QUEUED`, `...STARTED`, `...COMPLETED` / `...CANCELED` / `...FAILED` - `UnstructuredMaskingService.java:149`, `:216`, `:237`, `:242`, `:254` | **COVERED** | Actor, job/file identity, lifecycle outcomes, hash/size/count metadata are explicit. |
| `POST /api/unstructured/jobs/{id}/cancel` - `UnstructuredMaskingController.java:43` | Structured `UNSTRUCTURED_JOB_CANCEL_REQUESTED` and terminal cancellation - `UnstructuredMaskingService.java:187`, `:242` | **COVERED** | Explicit job identity/actor/outcome. |
| `DELETE /api/unstructured/jobs/{id}` - `UnstructuredMaskingController.java:44` | Legacy `UNSTRUCTURED_JOB_DELETED` - `UnstructuredMaskingService.java:208` | **PARTIAL** | Job identity/outcome inferred. |
| `GET /api/unstructured/jobs/{id}/download` - `UnstructuredMaskingController.java:46` | Structured `UNSTRUCTURED_OUTPUT_EXPORTED` - `UnstructuredMaskingController.java:49` | **COVERED** | Job/file identity, actor, outcome, format/size/hash metadata are explicit. |

Module total: **7 = 4 COVERED + 2 PARTIAL + 1 N/A-read-only**.

## Validation

| Endpoint and controller | Audit event(s) actually emitted | Status | Finding |
|---|---|---|---|
| `POST /api/validation/run` - `src/main/java/io/forgetdm/validation/ValidationController.java:18` | Legacy dynamic `VALIDATION_{result}` - `src/main/java/io/forgetdm/validation/ValidationService.java:143` | **PARTIAL** | Persists a report, but report/source identity and explicit outcome are not structured. |
| `POST /api/validation/reports/{id}/diagnose` - `ValidationController.java:28` | Legacy `VALIDATION_DIAGNOSED` - `src/main/java/io/forgetdm/validation/ValidationAdvisor.java:58` | **N/A-read-only** | Produces a diagnosis for an existing report; no governed object mutation. |
| `POST /api/validation/apply-fix` - `ValidationController.java:32` | Legacy `VALIDATION_FIX_APPLIED` - `ValidationAdvisor.java:88` | **PARTIAL** | Mutates a policy rule, but policy/rule identity, before/after values, and outcome are free-form. |

Module total: **3 = 2 PARTIAL + 1 N/A-read-only**.

## Virtualization

| Endpoint and controller | Audit event(s) actually emitted | Status | Finding |
|---|---|---|---|
| `POST /api/virtualization/snapshots` - `src/main/java/io/forgetdm/virtualization/VirtualizationController.java:59` | Legacy `VIRT_DSOURCE_SNAPSHOT` on success - `src/main/java/io/forgetdm/virtualization/VirtualizationService.java:215`; provider alternatives `ContainerVdbProvider.java:134`, `ZfsVdbProvider.java:438`, `:467` | **PARTIAL** | Asynchronous queue/failure/cancel outcomes are not a structured ledger lifecycle. |
| `POST /api/virtualization/operations/{id}/cancel` - `VirtualizationController.java:81` | None | **GAP** | Changes operation cancellation state without audit evidence. |
| `DELETE /api/virtualization/snapshots/{id}` - `VirtualizationController.java:86` | Legacy `VIRT_SNAPSHOT_DELETED` - `VirtualizationService.java:601`; provider alternatives `ContainerVdbProvider.java:275`, `ZfsVdbProvider.java:915` | **PARTIAL** | Resource/outcome inferred and provider paths vary. |
| `POST /api/virtualization/vdbs` - `VirtualizationController.java:95` | Legacy `VIRT_VDB_PROVISIONED` on success - `VirtualizationService.java:375`; alternatives `ContainerVdbProvider.java:194`, `ZfsVdbProvider.java:592`, `:703` | **PARTIAL** | No structured queue/start/failure lifecycle. |
| `DELETE /api/virtualization/vdbs/{id}` - `VirtualizationController.java:107` | Legacy `VIRT_VDB_DELETED` - `VirtualizationService.java:575`; alternatives `ContainerVdbProvider.java:265`, `ZfsVdbProvider.java:903` | **PARTIAL** | VDB/provider/result not explicit fields. |
| `POST /api/virtualization/vdbs/{id}/snapshots` - `VirtualizationController.java:116` | Legacy `VIRT_VDB_SNAPSHOT` or `VIRT_VDB_BOOKMARK` - `VirtualizationService.java:254`; alternatives `ContainerVdbProvider.java:230`, `ZfsVdbProvider.java:782` | **PARTIAL** | Resource/outcome inferred; failure not audited. |
| `POST /api/virtualization/vdbs/{id}/refresh` - `VirtualizationController.java:124` | Legacy dynamic restore action - `VirtualizationService.java:434`; alternatives `ContainerVdbProvider.java:251`, `ZfsVdbProvider.java:872` | **PARTIAL** | No structured queue/terminal outcome. |
| `POST /api/virtualization/vdbs/{id}/rewind` - `VirtualizationController.java:131` | Same legacy restore path - `VirtualizationService.java:434`; alternatives `ContainerVdbProvider.java:251`, `ZfsVdbProvider.java:872` | **PARTIAL** | Same limitation. |
| `POST /api/virtualization/datasources/{id}/logsync/enable` - `VirtualizationController.java:139` | Legacy `VIRT_LOGSYNC_ENABLED` - `src/main/java/io/forgetdm/virtualization/ZfsVdbProvider.java:208` | **PARTIAL** | Source/slot/outcome are detail/inference; unsupported/failure paths lack evidence. |
| `POST /api/virtualization/datasources/{id}/logsync/disable` - `VirtualizationController.java:144` | Legacy `VIRT_LOGSYNC_DISABLED` - `ZfsVdbProvider.java:224` | **PARTIAL** | Same limitation. |
| `POST /api/virtualization/environments` - `VirtualizationController.java:161` | Legacy `VIRT_ENV_CREATED` - `VirtualizationService.java:649` | **PARTIAL** | Environment id/host/outcome not structured. |
| `DELETE /api/virtualization/environments/{id}` - `VirtualizationController.java:166` | Legacy `VIRT_ENV_DELETED` - `VirtualizationService.java:663` | **PARTIAL** | Resource/outcome inferred. |

Module total: **12 = 11 PARTIAL + 1 GAP**.

## Prioritized AUD-001 blockers

1. **P0 - AUD-001-01 and AUD-001-02: close the 29 hard gaps.** Highest-risk gaps are DataScope nested design mutations (profiles, overrides, tool PKs, relationships, traversal rules), mainframe connection/copybook/mask CRUD, AI data-store changes, synchronous synthetic generation, virtualization cancel, and protected provision sample export. These operations currently leave no audit-ledger evidence at all.
2. **P0 - AUD-001-05: migrate the 151 legacy three-argument call sites.** The 125 affected endpoint rows cannot prove explicit resource identity/outcome from the caller. Replace them with `audit.record` (or a typed wrapper) and safe metadata; do not rely on action/detail parsing as the contract.
3. **P0 - AUD-001-03: structure governance decisions end to end.** Business-entity governance and both self-service approval families are legacy; enterprise self-service cancellation/comments and agent rejection are gaps. Approval/rejection must explicitly record request id, maker, checker, decision, reason/e-signature evidence, and action outcome.
4. **P1 - AUD-001-02: complete asynchronous failure/cancel lifecycles.** Virtualization records success-only legacy events and no operation-cancel event. Direct mapping loads and synchronous discovery/synthetic paths also omit structured queue/start/failure evidence.
5. **P1 - AUD-001-04: finish export/read evidence.** Audit CSV, mapping output, synthetic runner, mainframe generation/export, and unstructured output are covered. Self-service runner export and provision sample access are unlogged and block complete export/read traceability.
6. **P1 - attribution correctness:** many legacy calls pass `"system"`. `AuditService.resolveActor` may substitute the current request actor, but scheduled/async paths remain system-owned and the call site does not prove delegation/initiator. Structured lifecycle events should carry both initiating actor and worker/system executor where applicable.

## AUD-001 conclusion

The controller surface is **not yet fully covered**. Only **55/233 scoped endpoints are proven COVERED**; **154** are actionable audit debt (**125 PARTIAL + 29 GAP**), while **24** are intentionally classified read-only. The matrix therefore blocks a green result for AUD-001-01 through AUD-001-05 until the relevant gaps/partial paths are remediated and exercised with runtime evidence.
