import { describe, expect, it, vi } from 'vitest';
import { useModeMenu } from './useModeMenu';

function key(value: string): KeyboardEvent {
  return { key: value, preventDefault: vi.fn(), stopPropagation: vi.fn() } as unknown as KeyboardEvent;
}

describe('composer mode menu interaction', () => {
  it('opens with keyboard focus, cycles choices, closes with Escape, and does not change mode optimistically', () => {
    let disabled = false;
    let mode: 'build' | 'plan' = 'build';
    const emit = vi.fn();
    const focusChoice = vi.fn();
    const focusTrigger = vi.fn();
    const menu = useModeMenu({ disabled: () => disabled, current: () => mode, emit, focusChoice, focusTrigger });
    menu.triggerKeydown(key('ArrowDown'));
    expect(menu.open.value).toBe(true);
    expect(focusChoice).toHaveBeenLastCalledWith(0);
    menu.menuKeydown(key('ArrowDown'), 0);
    expect(focusChoice).toHaveBeenLastCalledWith(1);
    menu.choose('plan');
    expect(emit).toHaveBeenCalledWith('plan');
    expect(mode).toBe('build');
    expect(menu.open.value).toBe(false);
    expect(focusTrigger).toHaveBeenCalled();
    menu.show();
    menu.triggerKeydown(key('Escape'));
    expect(menu.open.value).toBe(false);
    disabled = true;
    menu.show();
    expect(menu.open.value).toBe(false);
  });
});
