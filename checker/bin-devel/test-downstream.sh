#!/bin/bash

set -e
# set -o verbose
set -o xtrace
export SHELLOPTS
echo "SHELLOPTS=${SHELLOPTS}"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &> /dev/null && pwd)"
source "$SCRIPT_DIR"/clone-related.sh

## downstream tests:  projects that depend on the Checker Framework.
## (There are none currently in this file.)
## These are here so they can be run by pull requests.
## Exceptions:
##  * plume-lib is run by test-plume-lib.sh
##  * daikon-typecheck is run as a separate CI project
##  * guava is run as a separate CI project

## This is moved to misc, because otherwise it would be the only work done by this script.
# # Checker Framework demos
# "$SCRIPT_DIR/.git-scripts/git-clone-related" eisop checker-framework.demos
# ./gradlew :checker:demosTests --console=plain --warning-mode=all
