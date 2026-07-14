# Enterprise Self-Service Capability Matrix

This matrix is an implementation and evidence checklist, not a marketing claim. Vendor behavior was checked against the official K2View and GenRocket material linked below.

| Capability | ForgeTDM status | Evidence |
|---|---|---|
| Governed product catalog | Implemented | Unified DataScope, Synthetic, Mapping, Reservation, and virtual-data products in `/api/self-service/v2/catalog`. |
| Safe request questionnaire | Implemented | Product-owned questionnaire, environment allowlist, purpose, test type, volume, variety, delivery, schedule, and reservation inputs. |
| Maker-checker approval | Implemented | Requester cannot approve their own request; approve/reject decisions are immutable events. |
| Guardrails | Implemented | Maximum volume, reservation duration, environment, enabled state, and artifact approval checks run server-side. |
| Fulfillment | Implemented | Launches the real DataScope, Synthetic, Mapping, Reservation, Provision/Refresh/Rollback engines. |
| Request lifecycle | Implemented | Draft-free request, approval queue, scheduled/ready/running/completed/failed/cancelled status and comment timeline. |
| API and runner support | Implemented | REST endpoints and token-safe shell launch/status commands. |
| Versioned products | Implemented | Published artifacts retain product settings and source artifact/version references. |
| Reservation/refresh/rollback | Implemented | Governed reservation and virtual data lifecycle product types. |
| Notifications and audit | Implemented | Order events, comments, audit events, and existing platform webhook integration. |
| Shared-schema SaaS tenant isolation | Not claimed | Current supported model remains deployment-per-client. Certification requires isolation and penetration evidence. |
| Vendor-certified connector breadth | Not claimed | Optional JDBC connectivity is tracked separately from certified, load-tested connector support. |

## Vendor references

- K2View test data management: https://www.k2view.com/solutions/test-data-management-tools/
- K2View TDM overview: https://support.k2view.com/Academy/articles/TDM/tdm_overview/01_tdm_overview.html
- K2View version 10 self-service and AI capabilities: https://www.k2view.com/k2view-version10-intro/
- GenRocket G-Portal: https://www.genrocket.com/wp-content/uploads/2024/11/Data-Sheet-G-Portal-2306-01.pdf
- GenRocket G-Questionnaire: https://www.genrocket.com/wp-content/uploads/2024/11/Data-Sheet-G-Questionnaire-2306-01.pdf
- GenRocket solution overview: https://www.genrocket.com/wp-content/uploads/2024/11/GenRocket-Solution-Overview-2312-01.pdf
