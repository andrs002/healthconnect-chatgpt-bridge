# MCP / ChatGPT integration design

For a commercial ChatGPT integration, expose backend reads as MCP tools, not direct database access.

Recommended tool surface:

- `health.get_profile()`
- `health.list_metrics()`
- `health.query_metrics(metric, start, end, aggregation?)`
- `health.get_sleep(start, end)`
- `health.get_workouts(start, end)`
- `health.get_body_composition(start, end)`

Each MCP request must be scoped to the authenticated user. Never accept an arbitrary `user_id` from the model without binding it to the authenticated account.

Production authentication:

1. User installs/opens the Android app.
2. User signs in with your identity provider.
3. Android app receives a short-lived access token.
4. App uploads health records using that token.
5. ChatGPT plugin/MCP performs OAuth against the same identity provider.
6. MCP server derives the user id from the OAuth subject (`sub`), not from model-provided text.
7. Backend returns only that user's records.

For public distribution, add:
- consent versioning
- token revocation
- account deletion
- data retention policy
- audit logging
- encryption
- tenant isolation
- abuse/rate-limit controls
