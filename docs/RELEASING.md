# Release packaging

Release versions are independent of the Schema Registry product version. The first integration release is `1.0.0`; its installation target is Schema Registry `1.1.2`.

## Prepare a release

1. Set the project version in `pom.xml` to a release version, without `-SNAPSHOT`.
2. Add a dated section to `CHANGELOG.md` and update the README download links.
3. Commit the source and run `./scripts/package-release.sh` using Java 17+ and Maven 3.9+ with `JAVA_HOME` pointing to that JDK.
4. Inspect `dist/`, verify `SHA256SUMS`, and test an extracted bundle. The script requires a clean Git worktree so its source, JAR, and commit identity agree.
5. Push the tested commit to `main` and wait for the Verify workflow to pass.
6. Create and push an annotated `v<version>` tag pointing at that commit.

The release workflow runs the verification/package steps, builds and publishes the GHCR image for `linux/amd64` and `linux/arm64`, uploads all release assets to a draft, and publishes the release only after these steps succeed. Repository Actions must permit the workflow's `contents: write` and `packages: write` permissions.

## Artifacts

- `schema-validation-micro-integration-<version>.tar.gz` and `.zip`: source, scripts, examples, documentation, prebuilt application/healthcheck JARs, SBOMs, and `RELEASE.json`.
- `schema-validation-micro-integration-<version>.jar`: executable Java 17 application.
- `bom.json` and `bom.xml`: CycloneDX runtime dependency inventories.
- `Schema-Validation-MI-Installation-Guide.docx`: installation guide.
- `SHA256SUMS`: SHA-256 checksums for the downloadable assets.
- `ghcr.io/solacese/schema-validation-micro-integration:<version>`: container image, also tagged `latest`.

Bundled installation detects the prebuilt JARs and skips the Maven Docker stage. A source checkout still builds from source. Neither route requires a host JDK for Docker installation. Initial Docker base-image downloads and the separately downloaded licensed registry distribution are still required.

The archive exporter uses Git's committed file list and normalized timestamps. It excludes Git internals, local credentials, runtime files, build output, downloaded registry distributions, and SDKPerf. Maven receives the commit timestamp through `project.build.outputTimestamp`; the dependency inventory and build metadata may still vary with the build toolchain.
