#!/usr/bin/env bash

sed -e "s|^    <version>[0-9]*\.[0-9]*\.[0-9]*</version>$|    <version>$1</version>|" -i pom.xml
sed -e "s|glacier-[0-9]*\.[0-9]*\.[0-9]*\.jar|glacier-$1.jar|" -i README.md
cd frontend
sed -e "s|^  \"version\": \"[0-9]*\.[0-9]*\.[0-9]*\"|  \"version\": \"$1\"|" -i package.json
npm install
#sed -e "s|^  "version": "0.0.4",$|  "version": "$1",|" -i package-lock.json
#sed -e "s|^      "version": "0.0.4",$|      "version": "$1",|" -i package-lock.json