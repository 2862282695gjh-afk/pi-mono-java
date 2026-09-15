import { nextTick, ref } from 'vue';
import { validateSessionName } from '../runtime/sessionName';

export function useSessionRename(rename: (sessionId: string, displayName: string) => Promise<void>, focusInput: () => void) {
  const renameId = ref('');
  const renameDraft = ref('');
  const renameError = ref('');
  const renamePending = ref(false);

  function begin(sessionId: string, title: string): boolean {
    if (renamePending.value) return false;
    renameId.value = sessionId; renameDraft.value = title; renameError.value = '';
    return true;
  }
  function cancel(): void {
    if (renamePending.value) return;
    renameId.value = ''; renameError.value = '';
  }
  async function save(): Promise<void> {
    const validation = validateSessionName(renameDraft.value);
    if (!validation.value) {
      renameError.value = validation.error ?? '名称不符合要求。';
      await nextTick(); focusInput(); return;
    }
    const targetId = renameId.value;
    renamePending.value = true; renameError.value = '';
    try {
      await rename(targetId, validation.value);
      if (renameId.value === targetId) renameId.value = '';
    } catch (error) {
      renameError.value = error instanceof Error ? error.message : '名称保存失败，请重试。';
      renamePending.value = false;
      await nextTick();
      if (renameId.value === targetId) focusInput();
    } finally { renamePending.value = false; }
  }
  return { renameId, renameDraft, renameError, renamePending, begin, cancel, save };
}
