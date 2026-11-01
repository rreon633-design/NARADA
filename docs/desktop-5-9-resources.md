# NARADA — Host resources and dependency inventory

> Observed during the single 30 October 2026 pass, before the post-pass corrections

## Narrow resource observation

One disposable Linux agent; two seconds of idle sampling, 30 owner snapshot RPCs and 20 small protected-draft commits. This is not a native Windows or large-transfer benchmark.

| Observation | Recorded value |
|---|---:|
| Startup to usable owner RPC | 154.708 ms |
| Idle sample | 2.001 s |
| Process CPU during sample, one-core basis | 0.5% |
| Resident memory | 28,160,000 bytes / about 26.86 MiB |
| Peak resident memory | 28,487,680 bytes / about 27.17 MiB |
| Snapshot RPC median / maximum | 2.196 / 4.596 ms |
| Protected draft commit median / maximum | 2.093 / 3.489 ms |
| Private-store files after fixture writes | 131,938 bytes |

[Raw report](../artifacts/desktop-5-9/resource-report.json). No inference is made about physical LAN throughput, large attachment memory, Windows DPAPI, storage controllers, battery use, sustained load or installer startup. The later recovery correction was not measured.

## Supply-chain output

The inventory generator completed and recorded:

- **810 components** from the Rust/npm lockfiles, including development and target-specific dependencies.
- **1,072 available license text copies**, each indexed with its hash.
- **4 unresolved declarations** in the metadata available to that generator: `axum 0.8.9`, `axum-core 0.5.6`, `matchit 0.8.4`, and `serde_path_to_error 0.1.20`.
- Source lockfile hashes and exact selected cryptographic dependency versions.

Unresolved declarations are not an assertion that those projects have no license. They are an inventory/review gap that must be resolved before release. The generated inventory was not silently regenerated to hide it.

- [CycloneDX inventory](../artifacts/desktop-5-9/sbom.cdx.json)
- [Paginated declarations](../artifacts/desktop-5-9/dependency-licenses.md)
- [License text index and lockfile provenance](../artifacts/desktop-5-9/supply-chain.json)
- [Generator output](../artifacts/desktop-5-9/lockfile-license-inventory.txt)
- [npm audit output: zero reported vulnerabilities](../artifacts/desktop-5-9/npm-audit.txt)

This is a **source-lockfile inventory**, not an exact shipped-installer SBOM, legal approval, signature or proof of vulnerability absence. RustSec review and ongoing advisory monitoring remain open. No production signing key or release artifact was generated.
