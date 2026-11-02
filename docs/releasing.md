# Releasing Upkeep

## A release

1. Set the new version in `VERSION` (for example `0.2.0`), add it to `CHANGELOG.md`, commit, and push.
2. Tag the commit and push the tag:

   ```bash
   git tag v0.2.0
   git push origin v0.2.0
   ```

3. The [release workflow](../.github/workflows/release.yml) checks that the tag matches `VERSION`, builds and tests
   everything on Windows with Eclipse Temurin 25, packages the Windows app with `jpackage`, and creates a **draft**
   release with:
   - `Upkeep-<version>-windows-x64.zip`: the app folder (`Upkeep.exe`, `app\`, `runtime\`). The runtime is a small Java
     made by `jlink` with only the modules Upkeep uses (`java.base`, `java.desktop`, `java.net.http`, `jdk.httpserver`).
   - `upkeep-<version>.jar`: for macOS and Linux (Java 21+).
   - `SHA256SUMS.txt`.
4. Open the draft on GitHub, check the notes and files, then publish it.

Every push also runs the [build workflow](../.github/workflows/build.yml): tests on Linux with Java 21, and the Windows
app with Java 25 (downloadable from the run's artifacts).

`Upkeep.exe` carries the product name, version, vendor and description that `jpackage` writes into it (SignPath requires
them). Its icon is drawn by `ui/AppIcon.java` at build time, the same drawing as `favicon.svg`.

## Code signing (SignPath Foundation)

An unsigned `.exe` makes Windows show "Windows protected your PC", and Smart App Control blocks it outright. The
[SignPath Foundation](https://signpath.org) signs open-source projects for free with its own certificate, which Windows
already trusts, so signed builds normally start without a warning. The publisher shown is "SignPath Foundation".

Their conditions (see [signpath.org/terms](https://signpath.org/terms)) and where Upkeep stands:

| condition | status |
|---|---|
| OSI-approved license, no proprietary parts | MIT, no dependencies |
| already released in the form to be signed | first release ships the unsigned app |
| built by CI from this repository | release workflow |
| product name and version in the binary | set by `jpackage` |
| "Code signing policy" section on the home page | in the README |
| MFA for everyone on the team | turn on two-factor authentication on GitHub |
| each signing request approved by hand | the approver in the README does it in SignPath |

Steps:

1. Publish the first (unsigned) release.
2. Apply at [signpath.org](https://signpath.org) with the repository URL.
3. Once accepted, in SignPath: create the project, an artifact configuration for the zip that signs `Upkeep.exe` inside
   it, and a signing policy. Note the organization ID, the project slug and the signing policy slug.
4. In the GitHub repository settings add the secret `SIGNPATH_API_TOKEN` (a SignPath CI user token).
5. In `release.yml`, replace the comment after "Build, test and package" with:

   ```yaml
      - id: unsigned
        uses: actions/upload-artifact@v4
        with:
          name: upkeep-windows-unsigned
          path: build/Upkeep-*-windows-x64.zip
      - uses: signpath/github-action-submit-signing-request@v1
        with:
          api-token: ${{ secrets.SIGNPATH_API_TOKEN }}
          organization-id: <organization id>
          project-slug: upkeep
          signing-policy-slug: release-signing
          github-artifact-id: ${{ steps.unsigned.outputs.artifact-id }}
          wait-for-completion: true
          output-artifact-directory: build/signed
   ```

   and attach `build/signed/*.zip` instead of the unsigned zip in the checksum and release steps.
6. Change the README's code signing paragraph to "Free code signing provided by SignPath.io, certificate by SignPath
   Foundation".
