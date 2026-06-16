#!/usr/bin/env node
/**
 * check-i18n-parity.mjs
 *
 * CI parity guard: verifies that every @@<id> referenced in Angular HTML
 * templates or TypeScript source files has a matching entry in the runtime
 * i18n catalog (messages.en.json), and reports catalog keys that are never
 * referenced anywhere (orphans).
 *
 * ID extraction strategy
 * ----------------------
 * HTML files (.html):
 *   - HTML comments are stripped before matching to avoid false-positives from
 *     comment text that happens to contain "@@<word>" (e.g. "keep @@ids stable").
 *   - Matches: i18n="@@id", i18n-<attr>="@@id"  (all Angular i18n attribute forms)
 *
 * TypeScript files (.ts, excluding .spec.ts):
 *   - Only lines containing "$localize" or "i18n=" are scanned to avoid
 *     false-positives from JSDoc/inline comments (e.g. "@@ids" in a comment).
 *   - Matches: $localize`:@@id:...` and $localize`:desc@@id:...` and
 *     inline i18n="@@id" in template strings.
 *
 * The @@id regex allows word characters, dots, and hyphens — covering all
 * ids actually used in this codebase (e.g. gdpr.legal-notice.heading).
 *
 * Orphan policy
 * -------------
 * Orphan keys (present in catalog, never referenced) are reported as WARNINGs
 * and do NOT cause a non-zero exit.  Rationale: some keys may be used by
 * future features, A/B variants, or runtime-only paths that are hard to grep
 * statically.  The warning still surfaces dead keys for human review.
 *
 * Exit codes:
 *   0 — all referenced keys are present in the catalog (warnings may be printed)
 *   1 — at least one template/TS references a key absent from the catalog
 *
 * Usage (from the frontend/ directory):
 *   node scripts/check-i18n-parity.mjs
 */

import { readFileSync } from 'node:fs';
import { readdir, readFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// ---------------------------------------------------------------------------
// Paths (relative to this script in frontend/scripts/)
// ---------------------------------------------------------------------------
const __dirname = fileURLToPath(new URL('.', import.meta.url));
const CATALOG_PATH  = resolve(__dirname, '../src/assets/i18n/messages.en.json');
const SOURCES_ROOT  = resolve(__dirname, '../src/app');

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

// Collect defined keys — skip the special @@locale metadata key
const definedKeys = new Set(
  Object.keys(catalog).filter(k => k !== '@@locale')
);

// ---------------------------------------------------------------------------
// 2. Scan source files
// ---------------------------------------------------------------------------

/**
 * Walk a directory tree and return every file whose name ends with one of
 * the given suffixes.
 */
async function walkDir(dir, suffixes) {
  const entries = await readdir(dir, { withFileTypes: true });
  const results = [];
  for (const entry of entries) {
    const fullPath = join(dir, entry.name);
    if (entry.isDirectory()) {
      results.push(...(await walkDir(fullPath, suffixes)));
    } else if (entry.isFile() && suffixes.some(s => entry.name.endsWith(s))) {
      results.push(fullPath);
    }
  }
  return results;
}

// @@<id> — id may contain word chars (\w), dots, and hyphens
// E.g.: gdpr.legal-notice.heading, share.dialog.create.button
const ID_PATTERN = /@@([\w][\w.-]*)/g;

// HTML comment block: <!-- ... --> (non-greedy, may span multiple lines)
const HTML_COMMENT_PATTERN = /<!--[\s\S]*?-->/g;

// Lines that carry actual i18n references in TypeScript source.
// Matches $localize template literals AND Angular i18n attribute forms
// (i18n="@@id" and i18n-<attr>="@@id") while avoiding plain-text comments
// like "keep @@ids stable" that contain "@@" but no i18n instrumentation.
function isI18nLine(line) {
  return line.includes('$localize') || line.includes('i18n=') || line.includes('i18n-');
}

/** @type {Map<string, Set<string>>} key → set of file paths referencing it */
const referencedKeyLocations = new Map();

function recordKey(key, filePath) {
  if (!referencedKeyLocations.has(key)) {
    referencedKeyLocations.set(key, new Set());
  }
  referencedKeyLocations.get(key).add(filePath);
}

// Collect HTML and TS files
const htmlFiles = await walkDir(SOURCES_ROOT, ['.html']);
const tsFiles   = (await walkDir(SOURCES_ROOT, ['.ts']))
  .filter(p => !p.endsWith('.spec.ts'));

// --- HTML files: strip comments, then extract all @@<id> ---
for (const filePath of htmlFiles) {
  const raw = await readFile(filePath, 'utf8');
  // Remove HTML comments to prevent comment text from matching as ids
  const content = raw.replace(HTML_COMMENT_PATTERN, '');
  for (const match of content.matchAll(ID_PATTERN)) {
    recordKey(match[1], filePath);
  }
}

// --- TypeScript files: scan only i18n-bearing lines ---
for (const filePath of tsFiles) {
  const raw = await readFile(filePath, 'utf8');
  for (const line of raw.split('\n')) {
    if (!isI18nLine(line)) continue;
    for (const match of line.matchAll(ID_PATTERN)) {
      recordKey(match[1], filePath);
    }
  }
}

const referencedKeys = new Set(referencedKeyLocations.keys());

// ---------------------------------------------------------------------------
// 3. Compute missing and orphan sets
// ---------------------------------------------------------------------------
const missingKeys   = [...referencedKeys].filter(k => !definedKeys.has(k));
const orphanKeys    = [...definedKeys].filter(k => !referencedKeys.has(k));
const okCount       = [...referencedKeys].filter(k => definedKeys.has(k)).length;

// ---------------------------------------------------------------------------
// 4. Report
// ---------------------------------------------------------------------------

// Errors (referenced in code but absent from catalog) — these break builds
if (missingKeys.length > 0) {
  console.error('');
  console.error('ERROR: Keys used in templates/TS but MISSING from messages.en.json:');
  for (const key of missingKeys.sort()) {
    const locations = [...referencedKeyLocations.get(key)]
      .map(p => p.replace(SOURCES_ROOT + '/', ''))
      .join(', ');
    console.error(`  @@${key}  →  ${locations}`);
  }
}

// Warnings (in catalog but never referenced — potential dead keys)
if (orphanKeys.length > 0) {
  console.warn('');
  console.warn('WARNING: Catalog keys NEVER referenced in any template or TS file:');
  console.warn('  (These may be used by future features or runtime paths not captured');
  console.warn('   by static analysis.  Review and remove if truly dead.)');
  for (const key of orphanKeys.sort()) {
    console.warn(`  ${key}`);
  }
}

// Summary line always printed
console.log('');
console.log(
  `i18n parity summary: ${okCount} OK` +
  `, ${missingKeys.length} MISSING (fatal)` +
  `, ${orphanKeys.length} orphans in catalog (warning)`
);

process.exit(missingKeys.length > 0 ? 1 : 0);
