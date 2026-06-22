import { SheriffConfig, sameTag, noDependencies } from '@softarc/sheriff-core';

/**
 * Sheriff architecture rules for the Glacier Angular frontend.
 *
 * Enforces two complementary axes (DDD + Angular best practices):
 *
 *  1. Bounded-context isolation (DDD strategic design)
 *     - domain:share  — the read-only share-view feature (a supporting subdomain)
 *     - domain:wall    — the host application (hashtag subscription wall)
 *     - domain:shared  — cross-cutting kernel (util, icons, framework-free models)
 *     Direction: wall → share → shared. The share context must NEVER reach back
 *     into the wall host (share ⇏ wall); shared depends only on shared.
 *
 *  2. Layer direction (tactical / clean architecture)
 *     ui → data → domain → util. Lower layers never depend on higher ones
 *     (a domain model must not import a service or a component).
 *
 * Verified by `npm run arch` (sheriff verify) — gated in CI (.github/workflows/verify.yml).
 */
export const config: SheriffConfig = {
  // Entry point of the dependency graph, so `sheriff verify` / `npm run arch`
  // can be run without an argument.
  entryFile: 'src/main.ts',
  enableBarrelLess: true,

  // Path → tags. Each module carries one `type:` tag (layer) and one `domain:`
  // tag (bounded context). The most specific path wins.
  modules: {
    'src/app': {
      // ── cross-cutting kernel (domain:shared) ─────────────────────────────
      'util': ['type:util', 'domain:shared'],
      'icons': ['type:util', 'domain:shared'],
      'model': ['type:domain', 'domain:shared'],
      'message-types': ['type:domain', 'domain:shared'],
      // Generic reusable QR widget — used by the share dialog; not wall-specific.
      'qr-code': ['type:ui', 'domain:shared'],

      // ── share bounded context (domain:share) ─────────────────────────────
      'share/model': ['type:domain', 'domain:share'],
      'share/services': ['type:data', 'domain:share'],
      'share/pipes': ['type:data', 'domain:share'],
      'share/guards': ['type:data', 'domain:share'],
      'share/components/<component>': ['type:ui', 'domain:share'],
      // share-dialog is the sharer-side UI of the share context (host renders it).
      'share-dialog': ['type:ui', 'domain:share'],

      // ── wall host domain (domain:wall) ───────────────────────────────────
      'services': ['type:data', 'domain:wall'],
      'fallback': ['type:data', 'domain:wall'],
      'wall': ['type:ui', 'domain:wall'],
      'hashtag': ['type:ui', 'domain:wall'],
      'toot': ['type:ui', 'domain:wall'],
      'header': ['type:ui', 'domain:wall'],
      'footer': ['type:ui', 'domain:wall'],
      'gdpr': ['type:ui', 'domain:wall'],
      'connection-status': ['type:ui', 'domain:wall'],
      'migration-banner': ['type:ui', 'domain:wall'],
    },
  },

  depRules: {
    // root = composition root / app shell (main.ts, app.module/component, and the
    // loose root-level services). It wires everything together → may use any tag.
    root: ['*'],
    noTag: ['*'],

    // ── bounded-context isolation ────────────────────────────────────────
    // 'root' = the loose app-root files: the composition root (app.module/main)
    // AND the wall's core services (subscription*/rx-stomp/animation), which live
    // directly under src/app. The share context and the shared kernel must NOT
    // reach into them — only the wall host may (it owns them).
    'domain:shared': [sameTag],
    'domain:share': [sameTag, 'domain:shared'],
    'domain:wall': [sameTag, 'domain:share', 'domain:shared', 'root'],

    // ── layer direction ─────────────────────────────────────────────────
    // domain + util stay strictly pure (no 'root' → a model/util can never pull
    // in a service or the shell). ui/data may consume the root-level services.
    'type:ui': [sameTag, 'type:data', 'type:domain', 'type:util', 'root'],
    'type:data': [sameTag, 'type:domain', 'type:util', 'root'],
    'type:domain': [sameTag, 'type:util'],
    'type:util': noDependencies,
  },
};
