#!/bin/bash

set -euo pipefail

if [ -z "${1:-}" ]; then
    echo 'Usage: ./repo_update.zsh "Describe the change"'
    exit 1
fi

git status
git add .
git diff --cached --check
git commit -m "$1"

git push -u origin HEAD
pr_url="$(gh pr create --fill)"
gh pr checks "$pr_url" --watch
gh pr merge "$pr_url" --squash --delete-branch
