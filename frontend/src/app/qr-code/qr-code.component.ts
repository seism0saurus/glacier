import {
  Component,
  Input,
  Output,
  EventEmitter,
  AfterViewInit,
  ViewChild,
  ElementRef,
  OnChanges,
  SimpleChanges,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Small QR badge for the main wall.
 *
 * Renders a QR code for the given `url` using the `qrcode` npm package
 * (pure-JS, no network).  On click/Enter/Space emits `openDialog`.
 *
 * Accessibility:
 * - `role="button"` with `aria-label` on the container.
 * - Keyboard activation: Enter and Space trigger the dialog.
 * - Focus ring via `:focus-visible` in styles.
 *
 * When `url` is null or empty, renders a '+' placeholder icon.
 *
 * ADR-SHARE-01 — lives in the main (eager) module.
 */
@Component({
  selector: 'app-qr-code',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule],
  template: `
    <button
      class="qr-badge"
      type="button"
      [attr.aria-label]="ariaLabel"
      data-testid="qr-badge-button"
      (click)="openDialog.emit()"
      (keydown.enter)="openDialog.emit()"
      (keydown.space)="$event.preventDefault(); openDialog.emit()"
    >
      @if (url) {
        <canvas
          #qrCanvas
          class="qr-canvas"
          [attr.aria-hidden]="true"
          data-testid="qr-canvas"
        ></canvas>
      } @else {
        <span class="qr-placeholder" aria-hidden="true">+</span>
      }
    </button>
  `,
  styles: [`
    .qr-badge {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 48px;
      height: 48px;
      padding: 4px;
      border: 2px solid var(--mat-sys-outline-variant, #ccc);
      border-radius: 4px;
      cursor: pointer;
      background: var(--mat-sys-surface-container-high, #f0f0f0);
      transition: border-color 0.15s;
    }
    .qr-badge:focus-visible {
      outline: 3px solid var(--mat-sys-primary, blue);
      outline-offset: 2px;
    }
    .qr-canvas {
      width: 40px;
      height: 40px;
    }
    .qr-placeholder {
      font-size: 24px;
      color: var(--mat-sys-on-surface, #333);
    }
  `],
})
export class QrCodeComponent implements AfterViewInit, OnChanges {

  /** The URL to encode in the QR code. Null/empty shows placeholder. */
  @Input() url: string | null = null;

  /** Aria label for the button. */
  @Input() ariaLabel: string = $localize`:@@share.qr.aria.no-link:Zugänglichen Link erstellen`;

  /** Emitted when the button is activated (click / Enter / Space). */
  @Output() openDialog = new EventEmitter<void>();

  @ViewChild('qrCanvas') canvasRef?: ElementRef<HTMLCanvasElement>;

  ngAfterViewInit(): void {
    this.renderQr();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['url']) {
      // Re-render after view is ready
      setTimeout(() => this.renderQr(), 0);
    }
  }

  /**
   * Renders the QR code to the canvas using the `qrcode` package.
   * Called after view init and when `url` changes.
   */
  private renderQr(): void {
    if (!this.url || !this.canvasRef?.nativeElement) {
      return;
    }

    // Dynamic import to keep initial bundle small and allow testing without the package
    import('qrcode').then((QRCode) => {
      if (this.canvasRef?.nativeElement && this.url) {
        QRCode.toCanvas(this.canvasRef.nativeElement, this.url, {
          width: 40,
          margin: 1,
          errorCorrectionLevel: 'M',
        }).catch((err: unknown) => {
          console.error('QR code render failed', err);
        });
      }
    }).catch((err) => {
      console.error('QR code library load failed', err);
    });
  }
}
