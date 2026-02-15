#!/bin/bash
# Bump version script for EUC OsmAnd Plugin
# Usage: ./bump_version.sh [major|minor|patch] [--auto-commit]

VERSION_FILE="version.properties"
AUTO_COMMIT=false

# Check for --auto-commit flag
for arg in "$@"; do
    if [ "$arg" = "--auto-commit" ]; then
        AUTO_COMMIT=true
    fi
done

if [ ! -f "$VERSION_FILE" ]; then
    echo "Error: $VERSION_FILE not found"
    exit 1
fi

# Read current version
MAJOR=$(grep VERSION_MAJOR "$VERSION_FILE" | cut -d= -f2)
MINOR=$(grep VERSION_MINOR "$VERSION_FILE" | cut -d= -f2)
PATCH=$(grep VERSION_PATCH "$VERSION_FILE" | cut -d= -f2)
CODE=$(grep VERSION_CODE "$VERSION_FILE" | cut -d= -f2)

echo "Current version: $MAJOR.$MINOR.$PATCH (code: $CODE)"

# Determine bump type
BUMP_TYPE=${1:-patch}

case "$BUMP_TYPE" in
    major)
        MAJOR=$((MAJOR + 1))
        MINOR=0
        PATCH=0
        ;;
    minor)
        MINOR=$((MINOR + 1))
        PATCH=0
        ;;
    patch)
        PATCH=$((PATCH + 1))
        ;;
    *)
        echo "Usage: $0 [major|minor|patch]"
        exit 1
        ;;
esac

# Always increment version code
CODE=$((CODE + 1))

echo "New version: $MAJOR.$MINOR.$PATCH (code: $CODE)"

# Update version.properties
cat > "$VERSION_FILE" << EOF
# Version configuration for EUC OsmAnd Plugin
# Update these values to bump version across all build files

VERSION_MAJOR=$MAJOR
VERSION_MINOR=$MINOR
VERSION_PATCH=$PATCH
VERSION_CODE=$CODE
EOF

echo "Updated $VERSION_FILE"
echo ""

# Auto-commit if flag is set, otherwise ask
if [ "$AUTO_COMMIT" = true ]; then
    REPLY="y"
else
    read -p "Commit and tag this version? (y/n) " -n 1 -r
    echo
fi

if [[ $REPLY =~ ^[Yy]$ ]]; then
    # Commit the version change
    git add "$VERSION_FILE"
    git commit -m "chore: Bump version to $MAJOR.$MINOR.$PATCH

- Version code: $CODE
- Updated via bump_version.sh"
    
    # Create annotated tag
    git tag -a "v$MAJOR.$MINOR.$PATCH" -m "Release v$MAJOR.$MINOR.$PATCH

Version: $MAJOR.$MINOR.$PATCH
Version Code: $CODE
Date: $(date -u +"%Y-%m-%d %H:%M:%S UTC")"
    
    echo "Committed and tagged as v$MAJOR.$MINOR.$PATCH"
    echo ""
    echo "Next steps:"
    echo "  1. Build: ./gradlew clean assembleDebug"
    echo "  2. Test the build"
    echo "  3. Push: git push && git push --tags"
else
    echo "Skipped commit and tag. Manual steps:"
    echo "  1. Build: ./gradlew clean assembleDebug"
    echo "  2. Test the build"
    echo "  3. Commit: git add version.properties && git commit -m 'chore: Bump version to $MAJOR.$MINOR.$PATCH'"
    echo "  4. Tag: git tag -a v$MAJOR.$MINOR.$PATCH -m 'Release v$MAJOR.$MINOR.$PATCH'"
fi
