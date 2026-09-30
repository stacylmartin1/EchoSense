#!/bin/bash

# Check if a branch name is included
if [ -z "$1" ]; then
    echo "Error: No branch name provided."
    echo "Usage: $0 \"your branch name\""
    exit 1
fi

# Start from an up-to-date main branch
git switch main
git pull --ff-only

# Create and switch to a new topic branch
git switch -c codex/"$1"

