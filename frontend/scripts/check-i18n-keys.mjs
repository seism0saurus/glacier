#!/usr/bin/env node
/**
 * check-i18n-keys.mjs
 *
 * CI lint script: validates that every @@<id> used in Angular HTML templates
 * has a matching entry in the runtime i18n catalog, and warns about catalog
 * keys that are never referenced in any template.
 *
 * Exit codes:
 *   0 — all referenced keys are present in the catalog (warnings may be printed)
 *   1 — at least one template references a key that is absent from the catalog
 *
 * Usage:
 *   node scripts/check-i18n-keys.mjs
 *
 * Run context: must be invoked from the `frontend/` directory (the pom.xml
 * exec wires it as `node scripts/check-i18n-keys.mjs` from workingDirectory=frontend).
 */

import { readFileSync } from 'node:fs';
import { readdir, readFile, stat } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// ---------------------------------------------------------------------------
// Paths — relative to this script's directory (frontend/scripts/)
// ---------------------------------------------------------------------------
const __dirname = fileURLToPath(new URL('.', import.meta.url));
const CATALOG_PATH = resolve(__dirname, '../src/assets/i18n/messages.en.json');
const TEMPLATES_ROOT = resolve(__dirname, '../src/app');

// ---------------------------------------------------------------------------
// 1. Load the catalog
// ---------------------------------------------------------------------------
let catalog;
try {
  const raw = readFileSync(CATALOG_PATH, 'utf8');
  catalog = JSON.parse(raw);
} catch (err) {
  console.error(`ERROR: Could not read catalog at ${CATALOG_PATH}: ${err.message}`);
  process.exit(1);
}

// Collect defined keys (skip the special @@locale key)
const definedKeys = new Set(
  Object.keys(catalog).filter(k => k !== '@@locale')
);

// ---------------------------------------------------------------------------
// 2. Recursively collect all HTML template files under src/app
// ---------------------------------------------------------------------------

/**
 * Walk a directory tree and collect all files matching the given suffix.
 * @param {string} dir   - Directory to start from.
 * @param {string} suffix - File suffix to match (e.g. '.html').
 * @returns {Promise<string[]>} Absolute paths of matching files.
 */
async function walkDir(dir, suffix) {
  const entries = await readdir(dir, { withFileTypes: true });
  const results = [];
  for (const entry of entries) {
    const fullPath = join(dir, entry.name);
    if (entry.isDirectory()) {
      results.push(...(await walkDir(fullPath, suffix)));
    } else if (entry.isFile() && entry.name.endsWith(suffix)) {
      results.push(fullPath);
    }
  }
  return results;
}

const templateFiles = await walkDir(TEMPLATES_ROOT, '.html');

// ---------------------------------------------------------------------------
// 3. Extract all @@<id> references from the templates
//    Matches both:
//      i18n="@@some.key"          (plain i18n attribute)
//      i18n-aria-label="@@some.key" (i18n-* attribute)
//    and the $localize tagged template literal form used in TS files:
//      @@some.key  (raw reference inside backtick strings)
// ---------------------------------------------------------------------------
const REFERENCE_PATTERN = /@@([\w.]+)/g;

/** Map from key → Set of template file paths that reference it. */
const referencedKeyLocations = new Map();

for (const filePath of templateFiles) {
  const content = await readFile(filePath, 'utf8');
  for (const match of content.matchAll(REFERENCE_PATTERN)) {
    const key = match[1];
    if (!referencedKeyLocations.has(key)) {
      referencedKeyLocations.set(key, new Set());
    }
    referencedKeyLocations.get(key).add(filePath);
  }
}

const referencedKeys = new Set(referencedKeyLocations.keys());

// ---------------------------------------------------------------------------
// 4. Compute missing and unreferenced sets
// ---------------------------------------------------------------------------
const missingKeys = [...referencedKeys].filter(k => !definedKeys.has(k));
const unreferencedKeys = [...definedKeys].filter(k => !referencedKeys.has(k));

// ---------------------------------------------------------------------------
// 5. Report
// ---------------------------------------------------------------------------
const okCount = [...referencedKeys].filter(k => definedKeys.has(k)).length;
const missingCount = missingKeys.length;
const unreferencedCount = unreferencedKeys.length;

// Errors first (referenced but missing from catalog)
if (missingKeys.length > 0) {
  console.error('');
  console.error('ERROR: The following keys are used in templates but missing from the catalog:');
  for (const key of missingKeys.sort()) {
    const locations = [...referencedKeyLocations.get(key)]
      .map(p => p.replace(TEMPLATES_ROOT + '/', ''))
      .join(', ');
    console.error(`  @@${key}  (in: ${locations})`);
  }
}

// Warnings (in catalog but never used in templates — may be used in TS files)
if (unreferencedKeys.length > 0) {
  console.warn('');
  console.warn('WARNING: The following catalog keys are not referenced in any template');
  console.warn('  (they may be used via $localize`` in TypeScript files — not an error):');
  for (const key of unreferencedKeys.sort()) {
    console.warn(`  ${key}`);
  }
}

// Summary
console.log('');
console.log(`i18n key check summary: ${okCount} keys OK, ${missingCount} missing (error), ${unreferencedCount} unreferenced (warning)`);

if (missingCount > 0) {
  process.exit(1);
}
