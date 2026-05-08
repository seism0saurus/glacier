// @ts-check
'use strict';

const tsParser = require('@typescript-eslint/parser');
const noShareDangerousHtml = require('./eslint-rules/no-share-dangerous-html');

/**
 * ESLint flat config for the Glacier Angular frontend.
 *
 * Primary purpose here: enforce the `no-share-dangerous-html` custom rule
 * across the share/ feature module to ensure the readonly view never uses
 * [innerHTML] or unsafe DomSanitizer bypass calls (ADR-SHARE-03).
 *
 * Run with: npm run lint
 * (which calls: npx eslint src/app/share/ src/app/subscription*.ts src/app/hashtag/hashtag.component.ts -c eslint.config.js)
 *
 * Note: Angular templates are TypeScript template literals in standalone
 * components; the rule inspects TemplateLiteral nodes for [innerHTML].
 * Full Angular template linting would require @angular-eslint, which is
 * a separate installation concern outside this scope — flag for DevOps.
 */

/** @type {import('eslint').Linter.FlatConfig[]} */
module.exports = [
  {
    files: ['src/app/share/**/*.ts'],
    languageOptions: {
      // TypeScript parser required to handle Angular decorators (@Component etc.)
      // and TypeScript-specific syntax (type annotations, generics, etc.)
      parser: tsParser,
      parserOptions: {
        ecmaVersion: 2022,
        sourceType: 'module',
      },
    },
    plugins: {
      'glacier-share': {
        rules: {
          'no-share-dangerous-html': noShareDangerousHtml,
        },
      },
    },
    rules: {
      'glacier-share/no-share-dangerous-html': 'error',
    },
  },
];
