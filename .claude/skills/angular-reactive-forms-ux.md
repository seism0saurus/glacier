---
name: angular-reactive-forms-ux
owner: "@seism0saurus"
description: Apply Angular Reactive Forms patterns with attention to validation UX, error-display timing, accessibility, and Signals interop in the Glacier frontend. TRIGGER when creating or modifying FormControl, FormGroup, FormArray, FormBuilder, NonNullableFormBuilder usage, validator composition, form templates with formControlName or formGroup bindings, or when the user mentions form validation, error message, updateOn, dirty, touched, validator, FormArray. SKIP for backend Java Bean Validation, server-side form handling, or non-form UI work.
---

# Reactive Forms UX for Glacier

## Always use `NonNullableFormBuilder` + typed forms

For initialized fields (the common case), `NonNullableFormBuilder` gives you clean types without `| null` noise:

```typescript
import { inject } from '@angular/core';
import { NonNullableFormBuilder, Validators } from '@angular/forms';

private fb = inject(NonNullableFormBuilder);

readonly form = this.fb.group({
  hashtag: this.fb.control('', {
    validators: [Validators.required, hashtagFormatValidator],
  }),
  includeReplies: this.fb.control(false),
});
```

Now `form.value.hashtag` is `string`, not `string | null`. Only fall back to regular `FormBuilder` when a field genuinely needs `null` as a valid state.

## `updateOn` — the UX-defining choice

`updateOn` controls **when validators run** and **when `valueChanges`/`statusChanges` fire**:

| Value | Behavior | Use for |
|---|---|---|
| `'change'` (default) | Every keystroke | Live search, instant filters, progress indicators |
| `'blur'` | When focus leaves the field | Most form inputs — prevents "errors flicker while typing" |
| `'submit'` | Only on form submission | Short forms where user wants "validate everything at once" |

```typescript
readonly form = this.fb.group({
  email: this.fb.control('', {
    validators: [Validators.required, Validators.email],
    updateOn: 'blur',   // don't scream at the user mid-typing
  }),
});
```

**Rule of thumb**: `'blur'` is the right default for most fields. Use `'change'` only when live feedback is genuinely the feature (e.g., password-strength meter, instant filter).

## Error display — always gate on `touched`

Don't show errors while the user hasn't interacted yet:

```html
@if (emailCtrl.invalid && (emailCtrl.dirty || emailCtrl.touched)) {
  <mat-error>…</mat-error>
}
```

- `touched` = field gained and lost focus.
- `dirty` = value has changed from initial.
- `pristine`/`untouched` = never touched, never changed.

The `touched || dirty` pattern covers: user focused-and-blurred (touched), or typed-without-blurring-yet-hit-submit (dirty).

## Show **one** error at a time, prioritized

```html
@if (emailCtrl.hasError('required')) {
  <mat-error i18n="@@email.error.required">E-Mail-Adresse ist erforderlich.</mat-error>
} @else if (emailCtrl.hasError('email')) {
  <mat-error i18n="@@email.error.format">Bitte eine gültige E-Mail-Adresse eingeben.</mat-error>
} @else if (emailCtrl.hasError('emailTaken')) {
  <mat-error i18n="@@email.error.taken">Diese E-Mail-Adresse ist bereits registriert.</mat-error>
}
```

Never concatenate multiple errors — the user can't tell which rule was violated.

## Cross-field validators — attach to the FormGroup

Not to an individual control:

```typescript
const passwordsMatch: ValidatorFn = (group: AbstractControl): ValidationErrors | null => {
  const pw = group.get('password')?.value;
  const confirm = group.get('confirm')?.value;
  return pw === confirm ? null : { passwordMismatch: true };
};

readonly form = this.fb.group({
  password: this.fb.control(''),
  confirm: this.fb.control(''),
}, { validators: [passwordsMatch] });
```

Error surfaces on the group: `form.hasError('passwordMismatch')`. Show it at the bottom of the group, not on either input.

## Async validators — always debounce + always complete

```typescript
import { debounceTime, distinctUntilChanged, first, map, switchMap } from 'rxjs/operators';

const hashtagUnique = (api: HashtagApi): AsyncValidatorFn => (control) =>
  control.valueChanges.pipe(
    debounceTime(400),
    distinctUntilChanged(),
    switchMap(v => api.checkUnique(v)),
    map(available => available ? null : { hashtagTaken: true }),
    first(),   // MUST complete — async validators that never complete leave the form PENDING
  );
```

Without `debounceTime`, every keystroke fires an API call. Without `first()`, the observable never completes and the form stays stuck in `PENDING` status.

## FormArray — dynamic collections

```typescript
readonly filters = this.fb.array<
  FormGroup<{ field: FormControl<string>; value: FormControl<string> }>
>([]);

addFilter() {
  this.filters.push(this.fb.group({
    field: this.fb.control(''),
    value: this.fb.control(''),
  }));
}
removeFilter(i: number) { this.filters.removeAt(i); }
```

In template, **always** track by the control identity — otherwise re-rendering loses focus and text selection:

```html
@for (filter of filters.controls; track filter) {
  <div [formGroup]="filter">
    <input formControlName="field" />
    <input formControlName="value" />
  </div>
}
```

## Signals interop

Convert form state to a signal for cleaner templates:

```typescript
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs/operators';

readonly formValue = toSignal(this.form.valueChanges, {
  initialValue: this.form.getRawValue(),
});
readonly isValid = toSignal(
  this.form.statusChanges.pipe(map(s => s === 'VALID')),
  { initialValue: this.form.valid }
);
```

Template:
```html
<button [disabled]="!isValid()" (click)="submit()">…</button>
```

No `| async` pipe needed, no lifecycle concerns.

## A11y wiring (see `angular-a11y-patterns` for the full treatment)

- Bind `[attr.aria-invalid]` to control validity + touched:
  ```html
  [attr.aria-invalid]="ctrl.invalid && ctrl.touched"
  ```
- Link input to error message via `aria-describedby="error-id"` + matching `<mat-error id="error-id">`.
- `mat-form-field` + `matInput` does most wiring automatically.

## Disabling controls — prefer form-state patterns, not runtime mutation

```typescript
// Bad — bypasses form dirty/touched tracking and Angular warnings
<input [disabled]="someFlag" formControlName="x">

// Good — set via FormControl API
if (someFlag) this.form.get('x')?.disable();
else this.form.get('x')?.enable();
```

Angular will warn if you use `[disabled]` on a reactive-forms-bound input.

## What Claude gets wrong without this skill

- Keeps default `updateOn: 'change'` everywhere → errors flicker mid-typing.
- Shows errors before `touched` → angry-red form on first render.
- Uses `FormBuilder` instead of `NonNullableFormBuilder` → `string | null` in types everywhere.
- Displays all errors at once via one `@if (ctrl.errors)` block → user can't tell which rule fired.
- Forgets `debounceTime` and/or `first()` in async validators → API spam or permanently `PENDING` form.
- Uses `[disabled]="…"` on reactive-bound inputs → Angular console warning, lost state tracking.

## References
- Reactive Forms API: https://angular.dev/guide/forms/reactive-forms
- Typed forms guide: https://angular.dev/guide/forms/typed-forms
- Glacier form usage: search `frontend/src/app` for `formGroup`/`formControlName` to find in-repo patterns
