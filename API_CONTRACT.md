# X-Clone API Contract & Error Handling

This document is the authoritative description of the X-Clone transport contract
and its error-handling semantics. It applies to both transports: the HTTP
transport (`POST /api`) and the legacy socket transport, which share the same
envelope format.

## Transport

The API is a single envelope endpoint:

```
POST /api
Content-Type: application/json
```

The HTTP transport accepts the exact same JSON `RequestEnvelope` that the socket
transport sends and returns the `ResponseEnvelope` produced by the shared
`RequestDispatcher`, serialized with the same Gson semantics.

### RequestEnvelope

```json
{
  "requestId": "uuid",
  "type": "TWEET_CREATE",
  "payload": { "...": "..." },
  "token": "session-token-optional"
}
```

| Field | Description |
|---|---|
| `requestId` | Client-generated correlation id (generated server-side if absent). |
| `type` | One of the `RequestType` values (e.g. `TWEET_LIKE`, `AUTH_LOGIN`). |
| `payload` | Operation-specific JSON payload. |
| `token` | Transport-level session token; most operations instead carry the token as `sessionToken` (or `token`) inside the payload. |

### ResponseEnvelope

```json
{
  "requestId": "uuid",
  "success": true,
  "type": "TWEET_CREATE_RESPONSE",
  "payload": { "...": "..." },
  "errorCode": null,
  "errorMessage": null,
  "timestamp": "2026-09-07T12:00:00Z"
}
```

| Field | Description |
|---|---|
| `success` | **The authoritative signal.** `true` for success, `false` for failure. |
| `type` | The response type for the requested operation (e.g. `TWEET_LIKE_RESPONSE`). The `type` value alone does not indicate success — always check `success`. |
| `payload` | The operation result on success; `null` on failure. |
| `errorCode` | `null` on success; a stable machine-readable code on failure. |
| `errorMessage` | `null` on success; a human-readable message on failure. |

Clients must rely on `success`, `errorCode` and `errorMessage` — never on the
HTTP status alone, and never on `type` alone.

## Success vs failure

- **Success:** `success=true`, `payload` = result, `errorCode`/`errorMessage` = `null`.
- **Failure:** `success=false`, `payload` = `null`, `errorCode`/`errorMessage` set.

## Error codes

### Route-specific business failures (HTTP 200)

Most business failures are represented as `Result.failure(...)` inside the use
cases and surface with a route-specific error code. These are **intentionally
HTTP 200**: the envelope `success` flag is the signal.

Examples: `TWEET_LIKE_FAILED`, `TWEET_GET_FAILED`, `AUTH_LOGIN_FAILED`,
`GET_PROFILE_FAILED`, `NOTIFICATION_READ_FAILED`.

### Standardized typed codes (HTTP status mapped)

Typed application exceptions (`AppException` and its subclasses) propagate their
`errorCode` into the envelope and are mapped to HTTP statuses by
`ErrorStatusMapper`:

| errorCode | HTTP status |
|---|---|
| `MALFORMED_JSON` | 400 |
| `VALIDATION_ERROR` | 400 |
| `UNKNOWN_REQUEST` | 400 |
| `UNSUPPORTED_MEDIA_TYPE` | 415 |
| `AUTH_REQUIRED` | 401 |
| `UNAUTHORIZED` | 401 |
| `FORBIDDEN` | 403 |
| `NOT_FOUND` | 404 |
| `CONFLICT` | 409 |
| `DATABASE_ERROR` | 500 |
| `UNEXPECTED_ERROR` | 500 |

Unknown error codes (and all successes) remain HTTP 200.

## HTTP-level behaviors

### Authentication

- **Anonymous operations** (registration, login, session refresh, password
  reset) never require credentials.
- **Protected operations** must present a valid session token. The token is read
  from the payload (`sessionToken`, with `token` as a fallback) or, when the
  payload carries no token field, from the envelope-level `token` field.
- Missing credentials → HTTP 401 + `errorCode=AUTH_REQUIRED`.
- Invalid/expired/revoked credentials → HTTP 401 + `errorCode=UNAUTHORIZED`.
- The HTTP gate is an additional layer; every authenticated use case still
  re-validates the session transactionally through
  `AuthLockOrchestrator.lockAndGetContextByToken(...)`.

### Malformed input

- Malformed JSON → HTTP 400 + `MALFORMED_JSON`.
- Empty request body → HTTP 400 + `MALFORMED_JSON`.
- Unknown request type → HTTP 400 + `UNKNOWN_REQUEST` (never `MALFORMED_JSON`).
- Unsupported media type → HTTP 415 + `UNSUPPORTED_MEDIA_TYPE`.

### Server-side failures

Infrastructure/session-resolver failures and unexpected exceptions are HTTP 500
with `errorCode=UNEXPECTED_ERROR` (or `DATABASE_ERROR`). They are never reported
as `MALFORMED_JSON`.

## Socket transport

The socket transport shares the same envelope format and the same
`RequestDispatcher`. It has no HTTP status; clients use `success`/`errorCode`/
`errorMessage` exactly as described above. HTTP status codes are an HTTP-only
addition and do not affect socket consumers.