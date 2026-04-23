---
name: @angular/localize setup requirements for Glacier
description: @angular/localize must be installed and in tsconfig.spec.json types array for $localize to work in tests
type: feedback
---

`@angular/localize` must be installed as a package AND listed in `tsconfig.spec.json` `types` array for the `$localize` tagged template to compile in Karma/Jasmine tests.

**Why:** Phase 2 implementation added it to tsconfig but not package.json; a linter then reverted tsconfig. The package and tsconfig entry are both needed — without the package, `$localize` types are not available; without the tsconfig entry, TypeScript doesn't pick up the types even if the package exists.

**How to apply:** When any component uses `$localize`, ensure:
1. `@angular/localize@<version matching other @angular/*>` is in `package.json` devDependencies
2. `"@angular/localize"` is in `tsconfig.spec.json` `compilerOptions.types`
3. `@angular/localize/init` is imported in `main.ts`
