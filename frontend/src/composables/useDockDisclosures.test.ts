import { describe, expect, it } from 'vitest';
import { useDockDisclosures } from './useDockDisclosures';

describe('dock disclosure coordination', () => {
  it('temporarily collapses both regions for Slash and restores each user choice', () => {
    const dock = useDockDisclosures();
    dock.commandExpanded.value = false;
    dock.progressExpanded.value = true;
    dock.setPaletteOpen(true);
    expect(dock.commandVisible.value).toBe(false);
    expect(dock.progressVisible.value).toBe(false);
    expect(dock.commandExpanded.value).toBe(false);
    expect(dock.progressExpanded.value).toBe(true);
    dock.setPaletteOpen(false);
    expect(dock.commandVisible.value).toBe(false);
    expect(dock.progressVisible.value).toBe(true);
  });
  it('defaults progress open for each new root and preserves the choice within one root', () => {
    const dock = useDockDisclosures();
    dock.observeRoot('root-a');
    dock.progressExpanded.value = false;
    dock.observeRoot('root-a');
    expect(dock.progressExpanded.value).toBe(false);
    dock.observeRoot('root-b');
    expect(dock.progressExpanded.value).toBe(true);
    dock.observeTerminal(true);
    expect(dock.progressExpanded.value).toBe(false);
    dock.observeRoot('root-c');
    expect(dock.progressExpanded.value).toBe(true);
    expect(dock.commandExpanded.value).toBe(true);
  });
});
