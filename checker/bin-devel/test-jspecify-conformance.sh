#!/bin/bash

set -e
# set -o verbose
set -o xtrace
export SHELLOPTS
echo "SHELLOPTS=${SHELLOPTS}"

SCRIPTDIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" > /dev/null 2>&1 && pwd)"
source "$SCRIPTDIR"/clone-related.sh
./gradlew assembleForJavac --console=plain -Dorg.gradle.internal.http.socketTimeout=60000 -Dorg.gradle.internal.http.connectionTimeout=60000

GIT_SCRIPTS="$SCRIPTDIR/.git-scripts"
"$GIT_SCRIPTS/git-clone-related" eisop jspecify-conformance
cd ../jspecify-conformance
./gradlew test --console=plain -PcfLocal
