# Security and Data Handling

Notification content can contain sensitive personal/location/incident information. The bearer is a high-value secret.

## Prohibited surfaces

Neither bearer nor captured content may appear in source/Git, build config, exported collector JSON, screenshots or screen recordings of protected views, Android backup/device transfer, ADB arguments, logcat, crash reports, analytics, clipboard by default, notification previews created by this app, worker prompts, or external-agent context.

## Required controls

- Android Keystore-backed bearer encryption with no post-save display.
- Explicit backup/data-extraction rules and device tests.
- Redacted structured logs and bounded retention.
- Room fields contain delivery/capture data but never bearer or Authorization headers.
- Secret-free diagnostics export; full-envelope inspection remains local and user-initiated.
- TLS certificate validation enabled; no cleartext operational traffic or trust-all code.
- External workers receive no credentials or captured notification content.
- Security scans compare active test secrets against source/artifacts without printing values.

The accepted design must decide whether `FLAG_SECURE` covers token entry only or also complete-envelope detail screens, balancing operational inspection against screen-exfiltration risk. Until accepted, tests must avoid screenshots of both.
