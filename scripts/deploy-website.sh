#!/bin/bash
# Deploy the EUC OsmAnd Plugin project website to the Oracle Cloud server.
#
# The site is a static directory (website/) served by nginx from
# /opt/apps/osmand-euc/website on oracle-cloud. The nginx vhost
# (/etc/nginx/sites-available/osmand-euc.a42r.com) is set up once and
# survives deploys.
#
# Usage: ./scripts/deploy-website.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="oracle-cloud:/opt/apps/osmand-euc/website/"

rsync -av --delete "${REPO_ROOT}/website/" "$DEST"

echo
echo "Deployed. https://osmand-euc.a42r.com/"
