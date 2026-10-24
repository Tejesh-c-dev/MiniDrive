# MD-013: Configurable CORS

MiniDrive applies one CORS policy through the Spring Security filter chain to `/api/**`. Configure the exact frontend origins with `MINIDRIVE_CORS_ALLOWED_ORIGINS`, as a comma-separated list such as:

```text
MINIDRIVE_CORS_ALLOWED_ORIGINS=https://drive.example.com,https://admin.example.com
```

Set this variable in the backend deployment environment to the real frontend origin or origins. No production domain is assumed by the repository. If the variable is unset, development defaults allow only `http://localhost:5173` and `http://127.0.0.1:5173`, matching Vite's default development port. A separately hosted frontend will not work until its origin is explicitly configured.

Origins must be exact `http://` or `https://` scheme and hostname values with an optional port. Paths, wildcards, and empty list entries are rejected at startup with an error identifying the invalid configuration. Scheme and port are part of the origin: `http://drive.example.com`, `https://drive.example.com`, and `https://drive.example.com:8443` are distinct values. Startup errors name configuration values but do not log request headers or other request contents.

The policy permits `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, and preflight `OPTIONS`, with `Authorization` and `Content-Type` request headers. These cover bearer JWTs, JSON, multipart upload, and `application/octet-stream` upload chunks used by the frontend. No response headers are exposed. Credentialed CORS remains disabled because the current frontend sends bearer JWTs in the `Authorization` header and does not use authentication cookies.

Browsers send a preflight `OPTIONS` request for cross-origin requests such as those with `Authorization`. Spring Security's CORS filter checks the configured origin, requested method, and headers before authentication. Diagnose a rejected preflight by checking the browser's requested origin, method, and header names against this list and verifying `MINIDRIVE_CORS_ALLOWED_ORIGINS` in the backend environment. Correct the exact allowlist value when needed; do not use wildcards or reflect the incoming origin. A successful preflight does not make the protected operation public: normal requests still require JWT authentication and existing authorization, token-revocation, and rate-limit checks.

CORS controls whether browser JavaScript can read cross-origin responses. It is not authorization and does not prevent non-browser clients from calling the API. No production deployment or browser-level request has been verified as part of MD-013.
