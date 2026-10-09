# SIbalsa acceptance requirements

Original implementation: Unlicense. Android/JDK/C++/Python standard and OS
libraries only; no third-party inference, training, FL, UI, HTTP, or testing
runtime dependency. Build tools are separate from runtime dependencies.

1. Reusable Android library, host-native library and model-free example APK.
2. Model/tokenizer and coordinator are supplied by the integrator. No downloaded
   pretrained weights, prompts, user histories or private server state in Git.
3. Independent inference/training runtimes and replaceable public interfaces.
4. Actual autoregressive transformer inference and local generation adaptation,
   implemented from scratch; no canned responses masquerading as inference.
5. Rich locally stored feedback: corrections, same-context preference pairs,
   ratings and reason metadata. Training objectives document what they consume.
6. Both training and every coordinator request require device idle, actual Wi-Fi,
   and charging; recheck during work and cancel when a condition changes.
7. TLS 1.2+ with trust-chain and hostname validation; no plaintext, trust-all or
   hostname-verification bypass switch. Development CA is explicitly configured.
8. Configurable resource, optimization, scheduling, model and server settings;
   security invariants cannot be disabled via flags.
9. No differential privacy and no account, persistent user/device identifier,
   raw conversation or feedback in update DTOs. TLS is not secure aggregation,
   and the coordinator can see individual numeric updates and source IPs.
10. Public enrollment with temporary credentials. Server validates payload,
    finite numbers, dimensions, model version, contribution bounds and replay.
    Source customization cannot be prevented; document this boundary honestly.
11. Real aggregation, durable versioning, evaluation/promotion and rollback.
12. Detailed README architecture, AI-assisted model integration instructions,
    build/install instructions, protocol, options and operational limitations.
13. Meaningful native gradient/numerical tests, JVM policy tests, real TLS/server
    adversarial tests, APK/AAR builds, Android emulator execution and integration.
14. Final provenance/license/secret/artifact review before authorized public
    publication to the user's GitHub repository named SIbalsa.

Do not mark a missing real-device performance benchmark as completed. Emulator
results demonstrate wiring and scheduling semantics, not flagship throughput.
