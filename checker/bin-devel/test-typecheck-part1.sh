#!/bin/bash

set -e
# set -o verbose
set -o xtrace
export SHELLOPTS
echo "SHELLOPTS=${SHELLOPTS}"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &> /dev/null && pwd)"
source "$SCRIPT_DIR"/clone-related.sh

# Pluggable type-checking:  run the Checker Framework on itself
./gradlew typecheck-part1 --console=plain --warning-mode=all

# NO-AFU
# # Also run the Checker Framework on AFU.
# ./gradlew assembleForJavac --console=plain --warning-mode=all
# ./gradlew :annotation-file-utilities:checkSignature --console=plain --warning-mode=all
