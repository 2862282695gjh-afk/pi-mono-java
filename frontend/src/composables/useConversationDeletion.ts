import { computed, ref } from 'vue';
import { RuntimeApiError, type RuntimeSession } from '../types/runtime';

export interface DeletionTarget { sessionId: string; title: string }
interface DeletionActions {
  read: (id: string) => Promise<RuntimeSession>;
  remove: (id: string) => Promise<void>;
  forget: (id: string) => void;
  environment: () => number;
}

/** 删除始终绑定打开菜单的行；结果未知时只读核对，不自动重发。 */
export function useConversationDeletion(actions: DeletionActions) {
  const target = ref<DeletionTarget | null>(null);
  const phase = ref<'checking' | 'ready' | 'running' | 'deleting' | 'uncertain' | 'error'>('checking');
  const error = ref('');
  let generation = 0;
  const pending = computed(() => phase.value === 'checking' || phase.value === 'deleting');
  const canDelete = computed(() => phase.value === 'ready');

  function close(): void {
    generation++;
    target.value = null;
    phase.value = 'checking';
    error.value = '';
  }
  function capture() {
    if (!target.value) return null;
    const selected = { ...target.value };
    const ticket = ++generation;
    const environment = actions.environment();
    return { selected, current: () => generation === ticket && environment === actions.environment() };
  }
  function missing(caught: unknown): boolean { return caught instanceof RuntimeApiError && caught.status === 404; }
  function finish(id: string): void { actions.forget(id); close(); }

  async function check(): Promise<void> {
    if (phase.value === 'deleting') return;
    const operation = capture();
    if (!operation) return;
    phase.value = 'checking'; error.value = '';
    try {
      const session = await actions.read(operation.selected.sessionId);
      if (!operation.current()) return;
      if (session.sessionId !== operation.selected.sessionId) throw new Error('Unexpected session');
      phase.value = session.state === 'running' ? 'running' : 'ready';
      if (phase.value === 'running') error.value = '会话正在执行，请先停止后再删除。';
    } catch (caught) {
      if (!operation.current()) return;
      if (missing(caught)) { finish(operation.selected.sessionId); return; }
      phase.value = 'error';
      error.value = '无法核对会话状态，请重试。';
    }
  }
  async function open(selected: DeletionTarget): Promise<void> {
    if (phase.value === 'deleting') return;
    target.value = { ...selected };
    await check();
  }
  async function confirm(): Promise<void> {
    if (!canDelete.value) return;
    const operation = capture();
    if (!operation) return;
    phase.value = 'deleting'; error.value = '';
    try {
      await actions.remove(operation.selected.sessionId);
      if (operation.current()) finish(operation.selected.sessionId);
    } catch (caught) {
      if (!operation.current()) return;
      if (missing(caught)) { finish(operation.selected.sessionId); return; }
      const uncertain = !(caught instanceof RuntimeApiError) || caught.outcomeUncertain;
      phase.value = uncertain ? 'uncertain' : 'error';
      error.value = uncertain ? '删除结果待确认，请先刷新状态。'
        : caught instanceof RuntimeApiError && caught.status === 409
          ? '会话状态已变化，请先刷新状态。' : '删除失败，会话已保留。';
    }
  }
  return { target, phase, error, pending, canDelete, open, close, check, confirm };
}
