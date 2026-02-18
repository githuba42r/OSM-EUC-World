# Release Process

This document describes the release process for EUC OsmAnd Plugin.

## Automated Release Script

The `release.sh` script automates the entire release process, ensuring consistency and proper versioning.

### Usage

```bash
./release.sh [patch|minor|major] [version_code]
```

**Parameters:**
- `patch|minor|major` (optional, default: `patch`) - Version bump type
  - `patch` - Increment patch version (1.1.6 → 1.1.7)
  - `minor` - Increment minor version and reset patch (1.1.6 → 1.2.0)
  - `major` - Increment major version and reset minor/patch (1.1.6 → 2.0.0)
- `version_code` (optional) - Explicit version code to use
  - If not provided, auto-increments from current code
  - Useful when Play Store has consumed version codes

### What the Script Does

1. **Checks Git Status** - Ensures working directory is clean
2. **Reads Current Version** - From `version.properties`
3. **Calculates New Version** - Based on bump type
4. **Confirms with User** - Asks for confirmation before proceeding
5. **Updates version.properties** - Writes new version numbers
6. **Commits Changes** - Commits version bump with standard message
7. **Creates Git Tag** - Tags commit as `v{version}` (e.g., `v1.1.7`)
8. **Builds Signed AAB** - Runs `./gradlew clean bundleRelease`
9. **Verifies Output** - Checks AAB file was created
10. **Shows Next Steps** - Displays deployment instructions

### Examples

**Standard patch release:**
```bash
./release.sh
# or
./release.sh patch
```

**Minor version release:**
```bash
./release.sh minor
```

**Patch release with specific version code:**
```bash
./release.sh patch 10
```

### Rollback on Failure

If the build fails, the script automatically:
- Deletes the created git tag
- Reverts the version commit
- Exits with error code

## Manual Release Process

If you need to release manually:

1. **Ensure git is clean:**
   ```bash
   git status
   ```

2. **Update version.properties:**
   ```properties
   VERSION_MAJOR=1
   VERSION_MINOR=1
   VERSION_PATCH=7
   VERSION_CODE=10
   ```

3. **Commit version bump:**
   ```bash
   git add version.properties
   git commit -m "chore: Bump version to 1.1.7 (code 10)"
   ```

4. **Create git tag:**
   ```bash
   git tag -a v1.1.7 -m "Release version 1.1.7"
   ```

5. **Build signed release:**
   ```bash
   ./gradlew clean bundleRelease
   ```

6. **Verify AAB:**
   ```bash
   ls -lh app/build/outputs/bundle/release/app-release.aab
   ```

## Version Numbering

### Version Name
Format: `MAJOR.MINOR.PATCH`
- Displayed to users in app info
- Example: `1.1.7`

### Version Code
- Integer that must increase with each release
- Used by Play Store to determine newest version
- Must be unique and higher than all previous uploads
- Example: `10`

### Synchronization

The release script ensures:
- Version name and code are always in sync
- Git tags match version names (`v1.1.7`)
- Commits are properly tagged before building
- AAB contains the correct version

## Deployment

After running the release script:

1. **Test the AAB locally** (optional but recommended)
   ```bash
   bundletool build-apks --bundle=app/build/outputs/bundle/release/app-release.aab \
     --output=test.apks \
     --mode=universal \
     --ks=app/release-key.jks \
     --ks-key-alias=osmand-euc-world-release-key
   ```

2. **Push to remote repository:**
   ```bash
   git push
   git push --tags
   ```

3. **Upload to Google Play Console:**
   - Go to [Play Console](https://play.google.com/console)
   - Navigate to your app → Release → Internal testing (or Production)
   - Create new release
   - Upload `app/build/outputs/bundle/release/app-release.aab`
   - Complete release notes
   - Review and roll out

## Version Code Issues

If you receive a "shadowed by higher version code" error:

1. Check highest version code in Play Console
2. Run release script with explicit version code:
   ```bash
   ./release.sh patch <higher_version_code>
   ```

Example: If Play Store has code 9, use:
```bash
./release.sh patch 10
```

## Best Practices

1. **Always use the release script** - Ensures consistency
2. **Don't skip version codes** - Keep sequential when possible
3. **Test before deploying** - Verify AAB on device if possible
4. **Write release notes** - Document changes for users
5. **Keep tags in sync** - Don't delete or modify release tags
6. **Backup keystore** - Store `app/release-key.jks` securely

## Troubleshooting

### "Git working directory is not clean"
Commit or stash your changes first:
```bash
git status
git add .
git commit -m "Your changes"
```

### "Build failed"
The script automatically rolls back. Check:
- Gradle daemon: `./gradlew --stop`
- Dependencies: `./gradlew dependencies`
- Clean build: `./gradlew clean`

### "AAB not found"
Check build output directory:
```bash
ls -la app/build/outputs/bundle/release/
```

## Release Checklist

Before running `./release.sh`:
- [ ] All changes committed
- [ ] Tests passing (if applicable)
- [ ] Privacy policy updated (if needed)
- [ ] Play Store listing updated (if needed)
- [ ] Keystore accessible (`app/release-key.jks`)
- [ ] Keystore credentials available

After release:
- [ ] AAB created successfully
- [ ] Git tag created
- [ ] Pushed to remote repository
- [ ] Uploaded to Play Console
- [ ] Release notes written
- [ ] Internal testing completed
- [ ] Production rollout initiated
