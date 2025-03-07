#!/usr/bin/env bash

dir=$(pwd)

cd "$dir"
sed -e "s|^    <version>[0-9]*\.[0-9]*\.[0-9]*</version>$|    <version>$1</version>|" -i pom.xml
sed -e "s|<glacier.version>[0-9]*\.[0-9]*\.[0-9]*</glacier.version>|<glacier.version>$1</glacier.version>|" -i pom.xml
sed -e "s|glacier-[0-9]*\.[0-9]*\.[0-9]*\.jar|glacier-$1.jar|" -i README.md

cd "$dir/frontend"
sed -e "s|^  \"version\": \"[0-9]*\.[0-9]*\.[0-9]*\"|  \"version\": \"$1\"|" -i package.json
npm install

cd "$dir/.github/"
sed -e "s|target-branch: [0-9]*\.[0-9]*\.[0-9]*|target-branch: $1|" -i dependabot.yaml
