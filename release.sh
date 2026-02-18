#!/bin/bash
# Release script for EUC OsmAnd Plugin
# Usage: ./release.sh [patch|minor|major] [version_code]
#   If version_code is provided, it will be used instead of auto-incrementing

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Default to patch version bump
BUMP_TYPE=${1:-patch}
SPECIFIED_CODE=${2:-}

echo -e "${GREEN}=== EUC OsmAnd Plugin Release Script ===${NC}"
echo ""

# 1. Check if git working directory is clean
echo "1. Checking git status..."
if [[ -n $(git status -s) ]]; then
    echo -e "${RED}Error: Git working directory is not clean!${NC}"
    echo "Please commit or stash your changes first:"
    git status -s
    exit 1
fi
echo -e "${GREEN}✓ Git working directory is clean${NC}"
echo ""

# 2. Read current version
echo "2. Reading current version..."
source version.properties
CURRENT_VERSION="${VERSION_MAJOR}.${VERSION_MINOR}.${VERSION_PATCH}"
CURRENT_CODE=${VERSION_CODE}
echo "   Current version: ${CURRENT_VERSION} (code ${CURRENT_CODE})"
echo ""

# 3. Calculate new version
echo "3. Calculating new version..."
NEW_MAJOR=$VERSION_MAJOR
NEW_MINOR=$VERSION_MINOR
NEW_PATCH=$VERSION_PATCH

# Use specified code if provided, otherwise increment
if [[ -n "$SPECIFIED_CODE" ]]; then
    NEW_CODE=$SPECIFIED_CODE
    echo -e "   ${YELLOW}Using specified version code: ${NEW_CODE}${NC}"
else
    NEW_CODE=$((VERSION_CODE + 1))
fi

case $BUMP_TYPE in
    major)
        NEW_MAJOR=$((VERSION_MAJOR + 1))
        NEW_MINOR=0
        NEW_PATCH=0
        ;;
    minor)
        NEW_MINOR=$((VERSION_MINOR + 1))
        NEW_PATCH=0
        ;;
    patch)
        NEW_PATCH=$((VERSION_PATCH + 1))
        ;;
    *)
        echo -e "${RED}Error: Invalid bump type '${BUMP_TYPE}'. Use: patch, minor, or major${NC}"
        exit 1
        ;;
esac

NEW_VERSION="${NEW_MAJOR}.${NEW_MINOR}.${NEW_PATCH}"
echo "   New version: ${NEW_VERSION} (code ${NEW_CODE})"
echo ""

# 4. Confirm with user
read -p "Continue with release ${NEW_VERSION}? (y/n) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo -e "${YELLOW}Release cancelled.${NC}"
    exit 0
fi
echo ""

# 5. Update version.properties
echo "4. Updating version.properties..."
cat > version.properties << EOF
# Version configuration for EUC OsmAnd Plugin
# Update these values to bump version across all build files

VERSION_MAJOR=${NEW_MAJOR}
VERSION_MINOR=${NEW_MINOR}
VERSION_PATCH=${NEW_PATCH}
VERSION_CODE=${NEW_CODE}
EOF
echo -e "${GREEN}✓ Version file updated${NC}"
echo ""

# 6. Commit version bump
echo "5. Committing version bump..."
git add version.properties
git commit -m "chore: Bump version to ${NEW_VERSION} (code ${NEW_CODE})"
echo -e "${GREEN}✓ Version committed${NC}"
echo ""

# 7. Create git tag
echo "6. Creating git tag..."
TAG_NAME="v${NEW_VERSION}"
git tag -a "$TAG_NAME" -m "Release version ${NEW_VERSION}"
echo -e "${GREEN}✓ Tag ${TAG_NAME} created${NC}"
echo ""

# 8. Build signed release
echo "7. Building signed release AAB..."
./gradlew clean bundleRelease
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Build successful${NC}"
else
    echo -e "${RED}Error: Build failed!${NC}"
    echo "Rolling back..."
    git tag -d "$TAG_NAME"
    git reset --hard HEAD~1
    exit 1
fi
echo ""

# 9. Verify AAB exists
AAB_PATH="app/build/outputs/bundle/release/app-release.aab"
if [ -f "$AAB_PATH" ]; then
    AAB_SIZE=$(ls -lh "$AAB_PATH" | awk '{print $5}')
    echo -e "${GREEN}✓ AAB created: ${AAB_PATH} (${AAB_SIZE})${NC}"
else
    echo -e "${RED}Error: AAB file not found!${NC}"
    exit 1
fi
echo ""

# 10. Summary
echo -e "${GREEN}=== Release Complete ===${NC}"
echo ""
echo "Version: ${NEW_VERSION} (code ${NEW_CODE})"
echo "Tag: ${TAG_NAME}"
echo "AAB: ${AAB_PATH}"
echo ""
echo -e "${YELLOW}Next steps:${NC}"
echo "1. Test the AAB file"
echo "2. Push to remote: git push && git push --tags"
echo "3. Upload ${AAB_PATH} to Google Play Console"
echo ""
