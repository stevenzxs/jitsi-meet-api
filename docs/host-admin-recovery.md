# Administrator host-transfer recovery (API foundation only)

This is not an end-to-end recovery procedure. Do not enable production host
transfer based on this API alone. Prosody must still verify the exact live target,
revoke old owners, deliver the new key privately, and verify target acknowledgement.

## Authorization

`POST /internal/host-recovery/v1/replace` requires `X-Host-Recovery-Secret`.
Spring property `host-recovery.admin-secret` (environment variable
`HOST_RECOVERY_ADMIN_SECRET`) defaults to empty/disabled. Use an independent
random secret of 32–256 characters. A secret equal to
`jitsi-token.role-check-secret` disables this endpoint. Do not expose the admin
credential to browsers, Jitsi JWTs, Prosody's ordinary role-check channel or logs.
Deployment must restrict this route to the trusted operator network and use TLS.
No deployment configuration or secret is enabled by this change.

## Request contract

Fields: `roomName`, `expectedOperationId`, `expectedRoomInstance`,
`expectedIdentity`, `operationId`, `targetSession`, `roomInstance`, `operatorId`,
`reason`. The operator must independently verify target XMPP membership and obtain
the current snapshot; a public identity is not authorization. `reason` should be
a non-sensitive incident/ticket reference, never a credential. `operatorId` is an
audit assertion made by the trusted caller, not independently authenticated identity.

The meeting must still be running and unexpired. The current authority must be
TRANSFERRING with a matching receipt instance/identity. That receipt may be expired.
The new operation must not already exist and the new instance must differ.

One transaction replaces the operation, rotates the host key, creates a 300-second
receipt and records recovery metadata. Authority remains TRANSFERRING. The response
contains only `operationId` and `identity`, with `Cache-Control: no-store`.
Use existing trusted target claim/confirmation operations after Prosody integration;
the admin endpoint never returns the new key.

Repeated requests conflict rather than rerotate or reissue credentials. If the HTTP
response is lost, query durable state over the trusted role channel. Do not submit
another replacement blindly. A committed recovery with an offline target remains
locked; this phase deliberately does not grant offline recovery or automatic unlock.

## Controlled continuation and replacement receipt

`POST /internal/host-recovery/v1/resume` uses the same independent administrator
authorization and accepts `roomName`, `previousOperationId`, `operationId`,
`targetSession`, and `roomInstance`. It verifies the persisted audit predecessor,
current TRANSFERRING operation, exact target/instance, receipt expiration, running
meeting and current credential identity. It returns only operation/identity with
`no-store`: no credential rotation, expiry extension, audit insertion or unlock.
Obtain the committed operation/target/instance from trusted durable status before
continuing after response loss. A different session needs a new authorized
replacement (or possession-proof recovery), not a resume bypass.

The ordinary internal claim response now includes nullable `previousOperationId`,
read from the recovery audit rather than supplied by the client. Prosody relays
this link only in its authenticated room credential message. Web accepts a new
operation over a live cached pending only when that attested predecessor matches
the pending operation exactly. Late old acknowledgements cannot complete the
superseding pending. No administrator secret is sent to Web.

## Verification

`mvn -q test`: SQLite integration covers expired receipts, stale snapshots, old
receipt invalidation, new-target completion, audit rollback, completed operations,
meeting expiration and preservation of creator recovery authority. Controller tests
cover disabled/incorrect/shared credentials and non-cacheable non-secret responses.
