# Security Policy

## Reporting a vulnerability

If GitHub private vulnerability reporting is available, use **Report a vulnerability** on this repository's [Security page](https://github.com/Misty4119/Auction-House-Sync/security). Include the affected version, deployment mode, reproduction steps, likely impact, and relevant redacted logs.

If that option is unavailable, open an issue requesting a private reporting channel without disclosing vulnerability details, secrets, or exploit steps. Wait for a maintainer to arrange a private route before sending sensitive information. No response time or supported-version window is promised here.

## System and trust boundaries

Auction-House-Sync is a Java plugin running inside a Canvas server process. Its scope includes commands and GUIs, auction persistence, Redis synchronization, configuration, and packaged dependencies.

- Players supply command arguments and auction items and interact with GUIs. Player access and moderator actions must respect the configured permissions and auction ownership.
- Server administrators control plugin installation, configuration, service credentials, node IDs, and the shared sync secret. Other installed plugins share the server process; this plugin is not a sandbox against malicious administrators or plugins.
- MySQL holds durable auction and metadata state. Redis holds cache data and transports cross-node events. Nodes with the shared sync secret participate in the same trust domain.
- Database and Redis endpoints are network boundaries. Operators must restrict access, provide appropriate service credentials, and configure TLS to match their services. A signed event does not encrypt transport or protect against a compromised trusted node.
- Configuration, logs, serialized items, auction state, and player identifiers may contain sensitive data. Reports and committed files must omit credentials, sync secrets, and unnecessary player information.

## Properties to preserve

These are review requirements, not claims that every path has been proven safe:

- Authorization and ownership checks precede privileged actions and auction/item/economy mutations.
- Untrusted input and cross-node events are validated before application; authentication, replay handling, and malformed-input paths warrant review.
- Persistence, cache updates, and recovery preserve consistent auction state. Duplicate claims, item duplication, lost items, and unauthorized currency transfers are meaningful security impacts.
- Player and world operations respect Canvas scheduler ownership even when triggered by database or Redis callbacks.
- Secrets are kept out of public output, and private dependency packaging preserves platform API boundaries.

## Findings and limitations

Report permission bypasses, injection, unsafe deserialization, secret exposure, event forgery/replay, reachable denial of service, and auction/economy integrity failures with their actual prerequisites and impact. No finding class is excluded by this policy. A deployment assumption or dependency on a trusted actor should inform reachability and severity rather than automatically suppress a finding.

TLS configuration, backend access control, backups, and the Vault economy provider depend on the operator and external services. Unit tests and startup/synchronization checks do not establish end-to-end financial atomicity, complete isolation, or production readiness. Dependency vulnerabilities should identify the affected packaged or runtime component and whether its vulnerable behavior is reachable.
