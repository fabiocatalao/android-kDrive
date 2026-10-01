# Fork sync

This fork is Infomaniak's kDrive plus a few commits of its own. The
[`Fork sync` workflow](../workflows/fork-sync.yml) keeps it that way:

- **Every day at 05:17 UTC, on every push to `main`, or on demand** (Actions → Fork sync → Run workflow):
  1. [`sync.sh`](sync.sh) rebases the fork's commits onto the latest upstream release tag.
  2. The release APK is built and signed with the fork's key.
  3. If the build passes, the rebased `main` is force-pushed and a GitHub release is published with the APK.
- **On pull requests to `main`**, it only builds; the APK is attached to the run as an artifact.
- If a commit doesn't rebase cleanly, or the result doesn't build, `main` is left untouched and an issue
  labelled `fork-sync` is opened and assigned to you, with the commands to fix it. The next successful build
  closes it.

`main` is rewritten every time upstream publishes a release, so update your clone with `git pull --rebase`.

## One-time setup

1. **Enable Actions** on the fork: Actions tab → *I understand my workflows, go ahead and enable them*.
2. **Disable Infomaniak's own workflows.** They are Infomaniak's internal CI and fail or wait forever in a fork.
   In the Actions tab, open each of *Android CI*, *Auto Author Assign*, *Dependent Issues*,
   *PR and Commit Message Check*, *Rebase Pull Request* and *Validate translations*, then
   **⋯ → Disable workflow**. This setting survives syncs.
3. **Add the secrets** (Settings → Secrets and variables → Actions → *New repository secret*):

   | Secret | Needed | Value |
   |---|---|---|
   | `SYNC_TOKEN` | Yes | A [fine-grained personal access token](https://github.com/settings/personal-access-tokens/new) with *Only select repositories* → this fork, and the permissions **Contents: Read and write** and **Workflows: Read and write**. The workflow's built-in token isn't allowed to push the rebased `main`, because rebasing rewrites commits that touch `.github/workflows`. |
   | `SIGNING_KEYSTORE_BASE64` | For releases | The keystore, base64-encoded: `base64 -w0 kdrive-fork.jks` (macOS: `base64 -i kdrive-fork.jks`). |
   | `SIGNING_KEYSTORE_PASSWORD` | For releases | The keystore password. |
   | `SIGNING_KEY_ALIAS` | For releases | The key alias. |
   | `SIGNING_KEY_PASSWORD` | No | The key password, if it differs from the keystore password. |
   | `GENIUS_SCAN_KEY` | No | A Genius Scan SDK licence for the `standard` flavor's document scanner. Without it the app builds, but the scanner isn't licensed. |

   Use the key you already sign your builds with: Android only installs an update that is signed with the
   same key as the installed app. If you don't have one yet, create it with
   `keytool -genkeypair -v -keystore kdrive-fork.jks -alias kdrive -keyalg RSA -keysize 4096 -validity 10000`
   and keep it safe. Without the signing secrets, builds still run, but the APK is unsigned and no release is
   published.
4. **Optionally, set variables** (same page, *Variables* tab):

   | Variable | Values |
   |---|---|
   | `KDRIVE_FLAVOR` | `standard` (default: push notifications through Firebase, Genius Scan document scanner) or `fdroid` (no Google services). |
   | `UPSTREAM_TRACK` | `release` (default: the latest upstream release tag) or `main` (upstream's development branch, which may be unstable). |

## Fixing a conflict or a failed build

The issue lists the exact commands. In short, on your machine:

```sh
git switch main
git pull --rebase origin main
.github/fork-sync/sync.sh        # the same rebase as CI; it stops at the first conflict
# fix the files, then: git add <files> && git rebase --continue
git push --force-with-lease origin main
```

`sync.sh` takes an upstream tag, branch or commit (`.github/fork-sync/sync.sh 5.22.1`) and reads the same
`UPSTREAM_TRACK` setting from the environment. Running `git config rerere.enabled true` once makes git
remember how you resolved a conflict, in case you abort and redo a rebase.

## Notes

- The app keeps the `com.infomaniak.drive` application ID but has a different signature, so it can't be
  installed over the Play Store or F-Droid app: those have to be uninstalled first.
- [`skip-sentry-upload.init.gradle`](skip-sentry-upload.init.gradle) is passed to Gradle in CI. kDrive's
  release builds upload source bundles and native symbols to Infomaniak's Sentry, which this fork has no
  credentials for, so those tasks are skipped.
- GitHub turns off scheduled workflows in public repositories after 60 days without activity. It sends an
  email first, and re-enabling the workflow is one click in the Actions tab.
- Each upstream release and each push to `main` adds a GitHub release; delete old ones whenever you like.
