/**
 * ESLint rule: no-share-dangerous-html
 *
 * Forbids the use of `[innerHTML]` bindings and `DomSanitizer` bypass
 * methods anywhere inside the `share/` feature module.
 *
 * The readonly share view must render ALL content via Angular interpolation
 * ({{ }}) only.  This rule provides a build-time enforcement of that
 * requirement (ADR-SHARE-03).
 *
 * Forbidden patterns inside share/ files:
 * - `[innerHTML]`         — in templates (caught here as template string literals
 *                           and TypeScript; Playwright a11y suite catches at runtime)
 * - `bypassSecurityTrustHtml`
 * - `bypassSecurityTrustScript`
 * - `bypassSecurityTrustStyle`
 * - `bypassSecurityTrustResourceUrl`
 * - `DomSanitizer.bypassSecurityTrustUrl`  — allowed; only the above are blocked
 *
 * Note: `bypassSecurityTrustUrl` (for `<a href>` bindings via SafeUrlPipe) IS
 * permitted inside share/ — only the HTML/Script/Style/ResourceUrl variants
 * are blocked.
 *
 * Integration: added to eslint.config.js (see frontend/eslint.config.js).
 */

'use strict';

/** @type {import('eslint').Rule.RuleModule} */
module.exports = {
  meta: {
    type: 'problem',
    docs: {
      description:
        'Forbid [innerHTML] and DomSanitizer bypass methods inside the share/ feature module',
      category: 'Security',
      recommended: false,
    },
    messages: {
      noInnerHtml:
        '[no-share-dangerous-html] [innerHTML] is forbidden in the share/ module. Use Angular {{ interpolation }} instead.',
      noBypassTrust:
        '[no-share-dangerous-html] DomSanitizer.{{ methodName }} is forbidden in the share/ module. Use SafeUrlPipe with bypassSecurityTrustUrl only.',
    },
    schema: [],
  },

  create(context) {
    // ESLint v9 flat config: context.filename is a property (not a method).
    // ESLint v8 legacy API: context.getFilename() is a method.
    // Support both to allow gradual migration.
    const filename =
      typeof context.filename === 'string'
        ? context.filename
        : (typeof context.getFilename === 'function' ? context.getFilename() : '');
    // Only activate for files inside the share/ feature module
    if (!filename.includes('/share/') && !filename.includes('\\share\\')) {
      return {};
    }

    const BLOCKED_BYPASS_METHODS = new Set([
      'bypassSecurityTrustHtml',
      'bypassSecurityTrustScript',
      'bypassSecurityTrustStyle',
      'bypassSecurityTrustResourceUrl',
    ]);

    return {
      // Catch [innerHTML] in template literal strings (e.g. template: `... [innerHTML] ...`)
      TemplateLiteral(node) {
        const raw = node.quasis.map((q) => q.value.raw).join('');
        if (/\[innerHTML\]/.test(raw)) {
          context.report({
            node,
            messageId: 'noInnerHtml',
          });
        }
      },

      // Catch .bypassSecurityTrust* method calls
      CallExpression(node) {
        const callee = node.callee;
        if (
          callee.type === 'MemberExpression' &&
          callee.property.type === 'Identifier' &&
          BLOCKED_BYPASS_METHODS.has(callee.property.name)
        ) {
          context.report({
            node,
            messageId: 'noBypassTrust',
            data: { methodName: callee.property.name },
          });
        }
      },

      // Catch property access (e.g. stored as a function reference)
      MemberExpression(node) {
        if (
          node.property.type === 'Identifier' &&
          BLOCKED_BYPASS_METHODS.has(node.property.name) &&
          // Avoid double-reporting CallExpression children
          node.parent?.type !== 'CallExpression'
        ) {
          context.report({
            node,
            messageId: 'noBypassTrust',
            data: { methodName: node.property.name },
          });
        }
      },
    };
  },
};
