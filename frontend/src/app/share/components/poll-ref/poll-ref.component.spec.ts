import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PollRefComponent } from './poll-ref.component';
import { PollView } from '../../model/readonly-toot-view';

describe('PollRefComponent', () => {
  let component: PollRefComponent;
  let fixture: ComponentFixture<PollRefComponent>;

  const mockPoll: PollView = {
    id: 'poll-1',
    options: [
      { title: 'Option A', votesCount: 10 },
      { title: 'Option B', votesCount: 5 },
    ],
    multiple: false,
    expired: false,
    votesCount: 15,
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PollRefComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(PollRefComponent);
    component = fixture.componentInstance;
    component.poll = mockPoll;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('renders a fieldset with data-testid="poll-ref"', () => {
    const fieldset = fixture.nativeElement.querySelector('[data-testid="poll-ref"]');
    expect(fieldset).not.toBeNull();
  });

  it('renders all radio inputs as disabled', () => {
    const inputs: NodeListOf<HTMLInputElement> = fixture.nativeElement.querySelectorAll('input[type="radio"]');
    expect(inputs.length).toBe(2);
    inputs.forEach((input) => expect(input.disabled).toBeTrue());
  });

  it('renders option text via interpolation (no innerHTML)', () => {
    component.poll = {
      ...mockPoll,
      options: [
        { title: '<script>alert(1)</script>', votesCount: 0 },
      ],
    };
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('script')).toBeNull();
  });

  it('has aria-label on fieldset', () => {
    const fieldset = fixture.nativeElement.querySelector('[data-testid="poll-ref"]');
    expect(fieldset.getAttribute('aria-label')).toBeTruthy();
  });

  it('shows vote counts when provided', () => {
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('10');
    expect(el.textContent).toContain('5');
  });
});
