# Health Connect → ChatGPT Bridge (MVP)

A sellable-architecture starter project for Android Health Connect → your backend → ChatGPT/MCP.

## Why this route

Google Fit cloud APIs are being deprecated/migrated toward Health Connect. Health Connect is the supported Android health-data layer. This bridge therefore reads Health Connect on-device, syncs to your backend, and exposes user-authorized data to ChatGPT.

## Architecture

1. Google Fit / Zepp / Tanita / other apps write to Health Connect.
2. Android bridge app obtains explicit Health Connect permissions.
3. The app syncs selected records to your backend.
4. Backend stores data per user and exposes read APIs.
5. A ChatGPT plugin/MCP server calls those APIs with user authorization.

## MVP data types

- Steps
- Heart rate
- Resting heart rate
- Sleep sessions
- Weight
- Body fat
- Exercise sessions

## Important commercial requirements before launch

- Google Play Health Connect policy/declaration
- Privacy Policy and Terms
- Explicit consent and granular permission screens
- Encryption in transit and at rest
- User delete/export controls
- Short-lived auth tokens / refresh tokens
- Audit logs
- Rate limits
- Data minimization
- Security review
- Health-data regional legal review (Taiwan + any country you sell in)

## Android setup

Open `android/` in Android Studio.

Replace:

- `BACKEND_BASE_URL`
- `USER_ACCESS_TOKEN`

with a proper sign-in/token flow before production.

This starter requests historical and background Health Connect access where supported.

## Backend setup

```bash
cd backend
python -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
uvicorn app.main:app --reload
```

Environment:

```bash
export DATABASE_URL=sqlite:///./bridge.db
export BRIDGE_MASTER_KEY=replace-me
```

## API shape

Android uploads normalized events:

`POST /v1/ingest`

ChatGPT-side tools read:

- `GET /v1/users/{user_id}/summary`
- `GET /v1/users/{user_id}/metrics?metric=steps&start=...&end=...`

## Production note

This repo is intentionally an MVP skeleton. Do not publish it unchanged. In particular, replace the simple bearer-token example with real OAuth/OIDC, add encrypted storage, and complete Play Console Health Connect review.
