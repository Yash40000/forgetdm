# ForgeTDM Frontend

Next.js + TypeScript App Router frontend for the next-generation ForgeTDM user experience.

## Run locally

Start Spring Boot first on port 8088, then:

```powershell
cd frontend
npm.cmd install
npm.cmd run dev
```

Open `http://localhost:3000/datascope`.

The Next dev server proxies `/api/*` to Spring Boot through `FORGETDM_API_BASE`.
Default:

```text
FORGETDM_API_BASE=http://localhost:8088
```

## Direction

The first migration target is DataScope. The existing Spring Boot static UI remains available while this Next app matures.
