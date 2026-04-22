---
name: angular-karma-jasmine-testing
owner: "@seism0saurus"
description: Write unit and component tests for Glacier's Angular 19 frontend using Karma + Jasmine with Standalone components, Signal testing, fakeAsync for async Observables, ComponentHarness for Material components, and STOMP/WebSocket mocking. TRIGGER when editing or creating files under frontend/src/**/*.spec.ts, configuring TestBed, ComponentFixture, fakeAsync/tick/flush, spyOn, ComponentHarness, or when the user mentions Karma, Jasmine, TestBed, component test, spec file, unit test for Angular. SKIP for Playwright/e2e tests (playwright-e2e-patterns, playwright-angular-a11y), backend tests (spring-boot-testing-patterns), or non-test code.
---

# Angular 19 Unit & Component Testing (Karma + Jasmine)

Glacier runs Karma + Jasmine for frontend unit tests (per `package.json` devDependencies: `karma`, `jasmine-core`, `karma-jasmine-html-reporter`, `karma-coverage`).

## Standalone Components — `imports`, not `declarations`

Angular 19 defaults to standalone components. In tests, put the component under test in `imports`, not `declarations`:

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ConnectionStatusComponent } from './connection-status.component';

describe('ConnectionStatusComponent', () => {
  let fixture: ComponentFixture<ConnectionStatusComponent>;
  let component: ConnectionStatusComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ConnectionStatusComponent],   // standalone → imports
      providers: [ /* mocks via useValue / useFactory */ ],
    }).compileComponents();

    fixture = TestBed.createComponent(ConnectionStatusComponent);
    component = fixture.componentInstance;
  });
});
```

**Claude's common mistake**: putting standalone components in `declarations` → Angular 19 throws `"Component X is standalone and cannot be declared in an NgModule"`. Always `imports`.

## Signal assertions — read as functions

Signals are called like functions in templates and tests:

```typescript
it('exposes loading state as a signal', () => {
  expect(component.isLoading()).toBe(false);
  component.startLoad();
  expect(component.isLoading()).toBe(true);
});

it('computed signal reflects source changes', () => {
  expect(component.formattedCount()).toBe('Keine Einträge');
  component.count.set(5);
  expect(component.formattedCount()).toBe('5 Einträge');
});
```

For `computed` or `effect`, you may need `TestBed.tick()` (Angular 18+) or `fixture.detectChanges()` to flush the reactive graph:

```typescript
component.sourceSignal.set(newValue);
TestBed.tick();                 // flush scheduled effects / computed updates
fixture.detectChanges();        // trigger template rebinding
expect(component.derivedSignal()).toBe(expected);
```

## `fakeAsync` + `tick` / `flush` for Observables and Promises

Synchronous assertions in `fakeAsync` zones without real timers:

```typescript
it('debounces hashtag input', fakeAsync(() => {
  component.hashtagInput.set('abc');
  tick(300);
  expect(apiService.search).not.toHaveBeenCalled();  // still within debounce
  tick(200);
  expect(apiService.search).toHaveBeenCalledWith('abc');
}));
```

- `tick(ms)`: advance virtual timer by ms.
- `flush()`: run all pending timers to completion.
- `discardPeriodicTasks()`: clean up intervals at test end.

**Claude's common mistake**: using `async/await` for timing-dependent tests → flaky, relies on real Zone scheduling. `fakeAsync` is the deterministic alternative.

## Mocking services — `useValue` / `jasmine.createSpyObj`

```typescript
beforeEach(async () => {
  const subscriptionService = jasmine.createSpyObj<SubscriptionService>(
    'SubscriptionService',
    ['subscribe', 'unsubscribe'],
    { messageObservable$: of([]) }  // properties as third arg
  );
  subscriptionService.subscribe.and.returnValue(of({ id: 'abc' }));

  await TestBed.configureTestingModule({
    imports: [WallComponent],
    providers: [{ provide: SubscriptionService, useValue: subscriptionService }],
  }).compileComponents();
});
```

## Mocking STOMP / WebSocket — Glacier-specific

Glacier uses `@stomp/rx-stomp` (see `rx-stomp.factory.ts`, `rx-stomp.config.ts`). For component tests that depend on STOMP, don't instantiate a real client — inject a test-controlled Subject:

```typescript
class StompStub {
  readonly connectionState$ = new BehaviorSubject<RxStompState>(RxStompState.OPEN);
  readonly messages = new Subject<Message>();
  watch(dest: string) { return this.messages.asObservable(); }
  publish(msg: IPublishParams) { /* no-op or record */ }
}

// In TestBed.providers:
{ provide: RxStomp, useValue: new StompStub() }
```

Driving a state transition:
```typescript
const stomp = TestBed.inject(RxStomp) as unknown as StompStub;
stomp.connectionState$.next(RxStompState.CLOSED);   // simulate disconnect
fixture.detectChanges();
expect(component.mode()).toBe('fallback');
```

## Reactive Forms testing

```typescript
it('shows required error after blur', fakeAsync(() => {
  const input = fixture.debugElement.query(By.css('input[formControlName="hashtag"]'));
  const control = component.form.controls.hashtag;

  control.setValue('');
  control.markAsTouched();
  fixture.detectChanges();

  const error = fixture.debugElement.query(By.css('mat-error'));
  expect(error.nativeElement.textContent).toContain('erforderlich');
}));
```

For `updateOn: 'blur'` controls, you must explicitly dispatch a blur event or call `markAsTouched` — `setValue` alone won't trigger validators under `'blur'` mode.

## Material Component Harnesses

Material provides test harnesses that abstract over Material's DOM structure — prefer them over raw selectors:

```typescript
import { HarnessLoader } from '@angular/cdk/testing';
import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { MatButtonHarness } from '@angular/material/button/testing';

let loader: HarnessLoader;

beforeEach(() => {
  loader = TestbedHarnessEnvironment.loader(fixture);
});

it('disables submit while form invalid', async () => {
  const button = await loader.getHarness(MatButtonHarness.with({ text: 'Abonnieren' }));
  expect(await button.isDisabled()).toBeTrue();
});
```

Harnesses are resilient to internal Material DOM changes (version upgrades don't break them the way CSS-selector-based tests do).

## HTTP testing — `HttpTestingController`

```typescript
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';

beforeEach(() => {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting()],
  });
});

it('fetches embed', () => {
  const http = TestBed.inject(HttpTestingController);
  service.fetch('https://example.com').subscribe(/* ... */);

  const req = http.expectOne('https://example.com');
  req.flush({ title: 'Hello' });
  http.verify();
});
```

## i18n — strings in tests

If tests match rendered text, they match **German** source (Glacier's compile-time language). Tests that assert English would break the `@@id`-based runtime replacement (there is no catalog loaded in Karma tests).

```typescript
// Good: matches source language
expect(el.textContent).toContain('Ihre Sitzung ist abgelaufen');

// Risky: matches translated text — there's no catalog in Karma
expect(el.textContent).toContain('Your session has expired');
```

See `angular-i18n-localize` skill for the catalog architecture.

## Running + coverage

```bash
cd frontend
npx ng test --watch=false --browsers=ChromeHeadless
npx ng test --code-coverage   # report in frontend/coverage/
```

The Maven build wires this via `frontend-maven-plugin`. Failing unit tests fail the Maven build too.

## What Claude gets wrong without this skill

- Puts standalone components in `declarations` → compile error on Angular 19.
- Uses `async/await` with real timers for debounce/throttle tests → flaky.
- Mocks `RxStomp` with `jest.fn()`-style APIs — Glacier uses Jasmine spies.
- Skips `markAsTouched()` in `updateOn: 'blur'` form tests → validators never fire.
- Uses raw CSS selectors instead of Material Harnesses → breaks on Material version upgrade.
- Asserts English strings that only exist after runtime catalog loading.
- Forgets `httpTesting.verify()` → false green on unasserted requests.

## References
- Existing spec examples: `frontend/src/app/**/*.spec.ts` (especially `subscription.service.spec.ts`, `connection-status.component.spec.ts`, `rx-stomp.spec.ts`)
- Karma config: `frontend/karma.conf.js` (if present) and `frontend/angular.json` `test` target
- Angular testing guide: https://angular.dev/guide/testing
- Material testing harnesses: https://material.angular.io/cdk/testing/overview
