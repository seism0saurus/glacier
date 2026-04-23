---
name: Phase 2 ws-fallback partial implementation warning
description: Phase 2 implementation record said 187 Karma tests, but working tree had fewer — some files had been reverted by linter or not fully saved
type: feedback
---

When starting Phase 3 fixes, several Phase 2 files were not in the working tree in their final form:
- `hashtag.component.ts` was at the pre-Phase-2 version (no CAP_EXCEEDED/RxStompService/MatSnackBar)
- `tsconfig.spec.json` was reverted to remove `@angular/localize` type
- `subscription-ack-message.d.ts` was missing the `rejection` field
- `app.module.ts` was missing `MatSnackBarModule`, `MatMenuModule`, `MatButtonModule`, `ConnectionStatusComponent`
- `@angular/localize` and `@axe-core/playwright` packages were not in `package.json` / `node_modules`

**Why:** A linter hook reverted some files during the Phase 2 implementation session. The implementation record accurately described the intended state but not all changes persisted.

**How to apply:** When starting a Phase 3 cycle, do a compilation check (`ng test --watch=false`) before assuming Phase 2 state is fully applied. Fix compilation errors before writing new tests.
