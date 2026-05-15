import { getGuestToHostBus } from '@craftercms/studio-ui/utils/subjects';
import { updateFieldValueOperation } from '@craftercms/studio-ui/state/actions/preview';
import { showSystemNotification } from '@craftercms/studio-ui/state/actions/system';
import { importRemoteImageToRepo, isImageUrlImportableOnDrop } from './aiAssistantImportApi';
import { DEFAULT_IMPORT_REPO_PATH } from './aiAssistantImportPath';

let installed = false;

function getStudioStore(): { getState: () => { sites?: { active?: string } }; dispatch: (a: unknown) => void } | null {
  try {
    const w = window as Window & {
      craftercms?: { getStore?: () => { getState: () => { sites?: { active?: string } }; dispatch: (a: unknown) => void } };
    };
    return w.craftercms?.getStore?.() ?? null;
  } catch {
    return null;
  }
}

function activeSiteId(): string {
  const store = getStudioStore();
  const id = store?.getState?.()?.sites?.active;
  return typeof id === 'string' ? id.trim() : '';
}

function isUpdateFieldOp(action: unknown): action is { type: string; payload: { value?: unknown } } {
  return (
    typeof action === 'object' &&
    action !== null &&
    'type' in action &&
    (action as { type: string }).type === updateFieldValueOperation.type &&
    'payload' in action &&
    typeof (action as { payload: unknown }).payload === 'object' &&
    (action as { payload: { value?: unknown } }).payload !== null
  );
}

/**
 * When Experience Builder drops an AI Assistant chat image, the guest sends {@code UPDATE_FIELD_VALUE_OPERATION} with
 * {@code value} set to the remote {@code https?://} URL or a raster {@code data:image/...;base64,...} payload. Studio's
 * write API expects a repo path. This bridge imports the image on drop, then forwards the operation with
 * {@code value} replaced by {@code /static-assets/...}.
 */
export function installRemoteImageDropImportBridge(): void {
  if (installed || typeof window === 'undefined') return;
  installed = true;

  const bus = getGuestToHostBus();
  const rawNext = bus.next.bind(bus);

  bus.next = (action: unknown) => {
    if (!isUpdateFieldOp(action)) {
      rawNext(action);
      return;
    }
    const payload = action.payload as Record<string, unknown>;
    const value = payload.value;
    if (typeof value !== 'string' || !isImageUrlImportableOnDrop(value)) {
      rawNext(action);
      return;
    }

    const site = activeSiteId();
    if (!site) {
      console.error('[aiassistant] Cannot import remote image on drop: no active site.');
      getStudioStore()?.dispatch?.(
        showSystemNotification({
          message: 'Cannot place remote image: no active site selected.'
        })
      );
      return;
    }

    void importRemoteImageToRepo(site, value, DEFAULT_IMPORT_REPO_PATH)
      .then((repoPath) => {
        rawNext({
          ...action,
          payload: {
            ...payload,
            value: repoPath
          }
        });
      })
      .catch((err: unknown) => {
        const msg = err instanceof Error ? err.message : String(err);
        console.error('[aiassistant] Remote image import on drop failed', err);
        getStudioStore()?.dispatch?.(
          showSystemNotification({
            message: `Could not import image on drop: ${msg}`
          })
        );
      });
  };
}
