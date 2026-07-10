# Next.js Frontend Migration

ForgeTDM is moving toward a product-grade UI while keeping Spring Boot as the backend API.

## Stack

- Next.js App Router
- TypeScript
- Mantine for the design system
- TanStack Query for server state
- TanStack Table for dense enterprise grids

## Why Next.js

The old static UI is hard to keep clean because page state, HTML strings, modals, grids, and job progress all live in one large browser script. Next.js gives ForgeTDM a product shell, route-level ownership, reusable components, and room for future team-facing/self-service flows beyond an internal admin console.

## First Migration Target

DataScope is the first route: `frontend/src/app/datascope/page.tsx`.

The first slice includes:

- DataScope workspace shell
- Blueprint creation and list
- Selected blueprint overview
- Table profile grid
- PII coverage and schema drift guardrails
- Saved job evidence panel

The existing Spring Boot static UI remains available until each workflow is migrated fully.

## Local Run

Start the backend on port 8088, then:

```powershell
cd frontend
npm.cmd install
npm.cmd run dev
```

Open:

```text
http://localhost:3000/datascope
```

API proxy:

```text
FORGETDM_API_BASE=http://localhost:8088
```
