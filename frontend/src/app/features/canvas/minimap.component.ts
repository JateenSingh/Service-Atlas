import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { GraphStore } from '../../core/state/graph.store';
import { LayoutStore, contentBounds } from '../../core/state/layout.store';
import { nodeColour } from './graph-canvas.component';

const MINIMAP_WIDTH = 168;
const MINIMAP_HEIGHT = 112;

/**
 * FR-5.7 — an overview of the whole graph for when the viewport only shows part of it.
 *
 * Deliberately non-interactive: it answers "where am I", and adding drag-to-navigate would compete
 * with the canvas's own pan without making anything easier.
 */
@Component({
  selector: 'sa-minimap',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (blips().length > 1) {
      <svg
        class="minimap"
        [attr.width]="width"
        [attr.height]="height"
        aria-hidden="true"
      >
        <rect class="frame" x="0.5" y="0.5" [attr.width]="width - 1" [attr.height]="height - 1" rx="6" />
        @for (blip of blips(); track blip.key) {
          <rect
            [attr.x]="blip.x"
            [attr.y]="blip.y"
            [attr.width]="blip.width"
            [attr.height]="blip.height"
            [attr.fill]="blip.colour"
            rx="1"
            [class.selected]="blip.selected"
          />
        }
      </svg>
    }
  `,
  styles: [
    `
      :host {
        display: block;
        pointer-events: none;
      }

      .minimap {
        display: block;
        border-radius: var(--radius-md);
        background: color-mix(in srgb, var(--bg-surface) 88%, transparent);
        box-shadow: var(--shadow-md);
      }

      .frame {
        fill: none;
        stroke: var(--border-subtle);
      }

      rect.selected {
        stroke: var(--accent);
        stroke-width: 1.5;
      }
    `,
  ],
})
export class MinimapComponent {
  private readonly graphStore = inject(GraphStore);
  private readonly layoutStore = inject(LayoutStore);

  readonly width = MINIMAP_WIDTH;
  readonly height = MINIMAP_HEIGHT;

  readonly blips = computed(() => {
    const layout = this.layoutStore.layout();
    const bounds = contentBounds(layout);
    if (!bounds) {
      return [];
    }
    const padding = 8;
    const scale = Math.min(
      (MINIMAP_WIDTH - padding * 2) / Math.max(bounds.width, 1),
      (MINIMAP_HEIGHT - padding * 2) / Math.max(bounds.height, 1),
    );
    const selection = this.graphStore.selection();

    return this.graphStore
      .visibleGraph()
      .nodes.flatMap((node) => {
        const position = layout.positions[node.key];
        if (!position) {
          return [];
        }
        return [
          {
            key: node.key,
            x: padding + (position.x - bounds.x) * scale,
            y: padding + (position.y - bounds.y) * scale,
            // Floors of 2px keep every service visible even on a very large graph.
            width: Math.max(2, position.width * scale),
            height: Math.max(2, position.height * scale),
            colour: nodeColour(node),
            selected: selection.kind === 'node' && selection.key === node.key,
          },
        ];
      });
  });
}
