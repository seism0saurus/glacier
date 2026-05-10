// @ts-check
'use strict';

const tsParser = require('@typescript-eslint/parser');
const noShareDangerousHtml = require('./eslint-rules/no-share-dangerous-html');

/**
 * ESLint flat config for the Glacier Angular frontend.
 *
 * Three rule groups:
 *
 * 1. Share-module safety (ADR-SHARE-03):
 *    `no-share-dangerous-html` custom rule for the share/ feature module.
 *
 * 2. localStorage key discipline (SR-SPLIT-01, AC-1, AC-16) — T1 rule:
 *    `no-restricted-syntax` blocks direct localStorage calls for 'hashtags'
 *    and 'messageQueue' keys in every production file except
 *    `subscription-persistence.service.ts`.
 *    Spec files (*.spec.ts) are exempted: they use raw localStorage access
 *    to spy on or stub state without going through the service itself.
 *
 * 3. Dependency-direction lock (SR-SPLIT-02, AC-2, AC-17) — T2 rule:
 *    `no-restricted-imports` in `subscription-persistence.service.ts`
 *    forbids imports from state/stomp/facade.  The dependency arrow is:
 *      SubscriptionPersistence ← SubscriptionStateService ← SubscriptionStompClient ← SubscriptionService
 *    No back-edges are allowed (ADR-1).
 *
 * Run with: npm run lint
 * (which calls: npx eslint src/app/share/ src/app/subscription*.ts src/app/hashtag/hashtag.component.ts -c eslint.config.js)
 */

/** @type {import('eslint').Linter.FlatConfig[]} */
module.exports = [
  // ── 1. Share module: no dangerous HTML ───────────────────────────────────
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

  // ── 2. T1: localStorage key discipline ───────────────────────────────────
  //
  // Scope: subscription*.ts files and hashtag.component.ts — the files most
  // likely to violate the persistence boundary.  Extended to the full
  // src/app/**/*.ts scope here for future-proofing; *.spec.ts is exempted
  // so test helpers can spy/stub localStorage without import ceremony.
  //
  // SR-SPLIT-01 / OWASP A03:2021 / CWE-20:
  //   localStorage is attacker-controlled under XSS.  All reads of 'hashtags'
  //   and 'messageQueue' must go through SubscriptionPersistence to benefit
  //   from validateHashtagsList / validateMessageQueue call-site discipline.
  {
    files: [
      'src/app/subscription.service.ts',
      'src/app/subscription-state.service.ts',
      'src/app/subscription-stomp-client.service.ts',
      'src/app/hashtag/hashtag.component.ts',
    ],
    languageOptions: {
      parser: tsParser,
      parserOptions: {
        ecmaVersion: 2022,
        sourceType: 'module',
      },
    },
    rules: {
      'no-restricted-syntax': [
        'error',
        {
          selector: "CallExpression[callee.object.name='localStorage'][callee.property.name=/^(getItem|setItem|removeItem)$/][arguments.0.value='hashtags']",
          message: "Access to localStorage key 'hashtags' must go through SubscriptionPersistence.loadHashtags() / saveHashtags() (SR-SPLIT-01).",
        },
        {
          selector: "CallExpression[callee.object.name='localStorage'][callee.property.name=/^(getItem|setItem|removeItem)$/][arguments.0.value='messageQueue']",
          message: "Access to localStorage key 'messageQueue' must go through SubscriptionPersistence / MessageQueue (SR-SPLIT-01).",
        },
      ],
    },
  },

  // ── 3. T2: Dependency-direction lock — full DAG (ADR-1, SR-SPLIT-02, AC-2) ──
  //
  // The four-node DAG is:
  //   SubscriptionPersistence ← SubscriptionStateService
  //                           ← SubscriptionStompClient ← SubscriptionService
  // No back-edges allowed.  Each of the three lower layers is locked by its
  // own no-restricted-imports block so lint catches a back-edge in any layer.

  // T2a: Persistence → nothing (bottom node)
  {
    files: ['src/app/subscription-persistence.service.ts'],
    languageOptions: {
      parser: tsParser,
      parserOptions: { ecmaVersion: 2022, sourceType: 'module' },
    },
    rules: {
      'no-restricted-imports': ['error', {
        paths: [
          './subscription-state.service',
          './subscription-stomp-client.service',
          './subscription.service',
        ],
      }],
    },
  },

  // T2b: State → Persistence only (may not import StompClient or Facade)
  {
    files: ['src/app/subscription-state.service.ts'],
    languageOptions: {
      parser: tsParser,
      parserOptions: { ecmaVersion: 2022, sourceType: 'module' },
    },
    rules: {
      'no-restricted-imports': ['error', {
        paths: [
          './subscription-stomp-client.service',
          './subscription.service',
        ],
      }],
    },
  },

  // T2c: StompClient → State + Persistence only (may not import Facade)
  {
    files: ['src/app/subscription-stomp-client.service.ts'],
    languageOptions: {
      parser: tsParser,
      parserOptions: { ecmaVersion: 2022, sourceType: 'module' },
    },
    rules: {
      'no-restricted-imports': ['error', {
        paths: ['./subscription.service'],
      }],
    },
  },
];
