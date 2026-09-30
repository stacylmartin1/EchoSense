#!/bin/bash

# Check if a commit message argument was provided
if [ -z "$1" ]; then
    echo "Error: No commit message provided."
    echo "Usage: $0 \"your commit message\""
    exit 1
fi

git status
git add .
git commit -m "Describe the change"

git push -u origin HEAD
gh pr create --fill
gh pr checks --watch
gh pr merge --squash --delete-branch

