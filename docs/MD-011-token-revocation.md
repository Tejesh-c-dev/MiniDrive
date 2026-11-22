# MD-011: Server-side access-token revocation

MiniDrive continues to issue 24-hour signed JWT access tokens. Each token now
has a random UUID `jti`. Logout revokes only the access token presented to the
authenticated `/api/auth/logout` endpoint. The backend stores the `jti` and
token expiry in PostgreSQL; it never stores the bearer token itself. Other
simultaneous logins remain valid until separately revoked or expired.

Every bearer token is signature- and expiration-validated and then checked
against the revocation table before its user is loaded into the security
context. A database lookup failure leaves the request unauthenticated, so
protected routes reject it. Logout returns success only after the revocation
record has been persisted. Repeating logout with that same token receives
`401` because the token is no longer accepted; this does not create another
record. Revocation records are removed in batches only after the token expiry
time. The JWT parser has no configured clock-skew allowance.
Tokens issued before this change do not contain a `jti` and must be rejected;
users with such tokens need to sign in again after deployment.

If logout cannot reach the server or the server cannot persist the record, the
browser still removes its local credential and reports that server revocation
was not confirmed. A copied, unexpired token may then remain usable until its
expiration or a later successful revocation. Token theft is not retroactively
prevented before the server commits revocation. Because credentials remain in
`localStorage`, same-origin script execution (including XSS) can read them;
revocation does not remove a copy already obtained by an attacker.

Refresh-token rotation is not implemented. This application currently has no
refresh-token issuance or refresh endpoint contract; the historical
`refresh_tokens` schema is unused by the current authentication service.
Refresh-token lifecycle and rotation are outside MD-011.
