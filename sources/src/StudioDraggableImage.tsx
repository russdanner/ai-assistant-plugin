import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Box, CircularProgress, Typography, useTheme } from '@mui/material';
import DragIndicatorRounded from '@mui/icons-material/DragIndicatorRounded';
import { useDispatch } from 'react-redux';
import { getHostToGuestBus } from '@craftercms/studio-ui/utils/subjects';
import {
  assetDragEnded,
  assetDragStarted,
  setPreviewEditMode
} from '@craftercms/studio-ui/state/actions/preview';
import { showSystemNotification } from '@craftercms/studio-ui/state/actions/system';
import type { SearchItem } from '@craftercms/studio-ui/models/Search';
import useActiveSiteId from '@craftercms/studio-ui/hooks/useActiveSiteId';
import { isProbablyRemoteImageUrl } from './aiAssistantImportApi';

/**
 * Turn an inline {@code data:image/...;base64,...} into an object URL for {@code <img src>}.
 * Avoids {@code fetch(data:...)} (blocked or flaky in some embeds) and keeps revoke scoped to the effect closure
 * so React 18 StrictMode cannot revoke a URL that state still references.
 */
function dataImageToObjectUrl(dataUrl: string): string | null {
  const s = dataUrl.trim();
  const comma = s.indexOf(',');
  if (comma < 0 || comma >= s.length - 1) return null;
  const header = s.slice(0, comma);
  if (!/;base64/i.test(header)) return null;
  const mimeMatch = /^data:([^;]+)/i.exec(s);
  const mime = mimeMatch?.[1]?.trim() || 'image/png';
  const b64 = s.slice(comma + 1).replace(/\s/g, '');
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return URL.createObjectURL(new Blob([bytes], { type: mime }));
}

function fileNameFromUrl(src: string): string {
  try {
    const u = new URL(src, typeof window !== 'undefined' ? window.location.origin : 'https://local');
    const seg = u.pathname.split('/').filter(Boolean).pop();
    if (seg && seg.includes('.')) return seg;
  } catch {
    // ignore
  }
  if (src.startsWith('/') && src.includes('.')) {
    const seg = src.split('/').filter(Boolean).pop();
    if (seg) return seg;
  }
  return 'image.png';
}

function mimeFromUrl(src: string): string {
  const lower = src.toLowerCase();
  if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) return 'image/jpeg';
  if (lower.endsWith('.webp')) return 'image/webp';
  if (lower.endsWith('.gif')) return 'image/gif';
  if (lower.endsWith('.svg')) return 'image/svg+xml';
  return 'image/png';
}

/**
 * Build a {@link SearchItem}-shaped payload for {@link assetDragStarted}, matching PreviewAssetsPanel / MediaCard.
 * Remote URLs stay as-is until drop; {@link installRemoteImageDropImportBridge} imports on {@code UPDATE_FIELD_VALUE_OPERATION}.
 */
export function searchItemFromImageSrc(src: string, alt?: string): SearchItem {
  const name = (alt && alt.trim()) || fileNameFromUrl(src);
  const now = new Date().toISOString();
  return {
    path: src,
    name,
    type: 'Image',
    mimeType: mimeFromUrl(src),
    previewUrl: src,
    lastModifier: '',
    lastModified: now,
    size: 0,
    snippets: ''
  };
}

export interface StudioDraggableImageProps {
  src?: string | null;
  alt?: string | null;
}

const DRAG_CARD_THUMB_W = 112;
const DRAG_CARD_THUMB_H = 72;

/**
 * Drag preview card. {@code setDragImage} must run synchronously in {@code dragstart}, so the blob used
 * for the thumbnail is prefetched earlier; we only clone an already-decoded {@code <img>} (hidden blob
 * preview or the visible chat image). Import/drop still uses the original URL path.
 */
function attachRepresentativeDragCard(
  e: React.DragEvent,
  title: string,
  sourceImg: HTMLImageElement | null,
  blobThumbImg: HTMLImageElement | null,
  useBlobThumb: boolean
): (() => void) | void {
  const dt = e.dataTransfer;
  if (!dt) return;
  dt.effectAllowed = 'copy';

  const pad = 8;
  const el = document.createElement('div');
  el.style.cssText = `position:fixed;right:12px;bottom:12px;width:132px;padding:${pad}px;background:linear-gradient(145deg,#2d2d2d,#1a1a1a);color:#fff;border-radius:8px;z-index:2147483647;box-shadow:0 6px 20px rgba(0,0,0,0.4);pointer-events:none;border:1px solid rgba(255,255,255,0.12)`;

  let thumbEl: HTMLImageElement | null = null;
  const blobReady =
    useBlobThumb &&
    blobThumbImg &&
    blobThumbImg.complete &&
    blobThumbImg.naturalWidth > 0;
  const sourceReady =
    sourceImg &&
    sourceImg.complete &&
    (sourceImg.naturalWidth > 0 || sourceImg.getBoundingClientRect().width > 0);

  if (blobReady && blobThumbImg) {
    const thumb = blobThumbImg.cloneNode(true) as HTMLImageElement;
    thumb.removeAttribute('draggable');
    thumb.style.cssText = `display:block;width:${DRAG_CARD_THUMB_W}px;height:${DRAG_CARD_THUMB_H}px;object-fit:cover;border-radius:4px;margin-bottom:6px;background:#1a1a1a`;
    el.appendChild(thumb);
    thumbEl = thumb;
  } else if (sourceReady && sourceImg) {
    const thumb = sourceImg.cloneNode(true) as HTMLImageElement;
    thumb.removeAttribute('draggable');
    thumb.style.cssText = `display:block;width:${DRAG_CARD_THUMB_W}px;height:${DRAG_CARD_THUMB_H}px;object-fit:cover;border-radius:4px;margin-bottom:6px;background:#1a1a1a`;
    el.appendChild(thumb);
    thumbEl = thumb;
  } else {
    const icon = document.createElement('div');
    icon.textContent = '🖼';
    icon.style.cssText = 'font-size:22px;line-height:1;margin-bottom:4px;text-align:center';
    el.appendChild(icon);
  }

  const line2 = document.createElement('div');
  line2.textContent = title.length > 42 ? `${title.slice(0, 39)}…` : title || 'Image';
  line2.style.cssText = 'font:11px/1.35 system-ui,sans-serif;opacity:0.92;word-break:break-word';
  el.appendChild(line2);

  const line3 = document.createElement('div');
  line3.textContent = 'Drop on image field';
  line3.style.cssText = 'font:10px/1.2 system-ui,sans-serif;opacity:0.65;margin-top:4px';
  el.appendChild(line3);

  document.body.appendChild(el);
  void el.offsetHeight;

  const sr = sourceImg?.getBoundingClientRect();
  let hx = el.offsetWidth / 2;
  let hy = el.offsetHeight / 2;
  if (thumbEl && sr && sr.width > 0 && sr.height > 0) {
    const ox = Math.max(0, Math.min(e.clientX - sr.left, sr.width));
    const oy = Math.max(0, Math.min(e.clientY - sr.top, sr.height));
    hx = pad + (ox / sr.width) * DRAG_CARD_THUMB_W;
    hy = pad + (oy / sr.height) * DRAG_CARD_THUMB_H;
  }

  try {
    dt.setDragImage(el, hx, hy);
  } catch {
    el.remove();
    return;
  }
  return () => {
    el.remove();
  };
}

export default function StudioDraggableImage(props: Readonly<StudioDraggableImageProps>) {
  const { src, alt } = props;
  const theme = useTheme();
  const dispatch = useDispatch();
  const imgRef = useRef<HTMLImageElement | null>(null);
  const blobImgRef = useRef<HTMLImageElement | null>(null);
  const blobUrlRef = useRef<string | null>(null);
  const removeGhostRef = useRef<(() => void) | null>(null);
  const activeSiteId = useActiveSiteId();
  const effectiveSite = activeSiteId?.trim() || '';

  const [blobPreviewUrl, setBlobPreviewUrl] = useState<string | null>(null);
  /** When set, {@code <img>} uses the object URL (CSP-safe) instead of the wire {@code src}. */
  const [blobDecoded, setBlobDecoded] = useState(false);
  const [blobLoading, setBlobLoading] = useState(false);

  useEffect(() => {
    return () => {
      removeGhostRef.current?.();
      removeGhostRef.current = null;
    };
  }, []);

  const trimmed = src?.trim() ?? '';
  const isRemote = trimmed ? isProbablyRemoteImageUrl(trimmed) : false;

  /**
   * Prefer an object URL for {@code <img src>}: Studio CSP often allows {@code blob:} while blocking {@code data:},
   * and revoking is tied to this effect's closure (avoids React 18 StrictMode revoking a URL state still points at).
   * Drop/import still uses the original {@code trimmed} wire URL.
   */
  useEffect(() => {
    const ac = new AbortController();
    let objectUrl: string | null = null;
    const revokeOwned = () => {
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
        objectUrl = null;
      }
      blobUrlRef.current = null;
    };

    setBlobPreviewUrl(null);
    setBlobDecoded(false);
    setBlobLoading(false);

    if (!trimmed) {
      return () => {
        ac.abort();
        revokeOwned();
      };
    }

    if (/^data:image\//i.test(trimmed)) {
      setBlobLoading(true);
      try {
        const u = dataImageToObjectUrl(trimmed);
        if (u && !ac.signal.aborted) {
          objectUrl = u;
          blobUrlRef.current = u;
          setBlobPreviewUrl(u);
          setBlobDecoded(true);
        } else if (!ac.signal.aborted) {
          setBlobDecoded(true);
        }
      } catch {
        if (!ac.signal.aborted) {
          setBlobDecoded(true);
        }
      }
      if (!ac.signal.aborted) {
        setBlobLoading(false);
      }
      return () => {
        ac.abort();
        revokeOwned();
      };
    }

    const fetchUrl =
      trimmed.startsWith('http://') || trimmed.startsWith('https://')
        ? trimmed
        : new URL(trimmed, typeof window !== 'undefined' ? window.location.href : 'https://local').href;

    const init: RequestInit = {
      signal: ac.signal,
      credentials: isProbablyRemoteImageUrl(trimmed) ? 'omit' : 'same-origin',
      mode: isProbablyRemoteImageUrl(trimmed) ? 'cors' : 'same-origin'
    };

    setBlobLoading(true);
    void fetch(fetchUrl, init)
      .then((r) => {
        if (!r.ok) throw new Error(String(r.status));
        return r.blob();
      })
      .then((blob) => {
        if (ac.signal.aborted) return;
        const u = URL.createObjectURL(blob);
        objectUrl = u;
        blobUrlRef.current = u;
        setBlobPreviewUrl(u);
        setBlobDecoded(true);
        setBlobLoading(false);
      })
      .catch(() => {
        if (ac.signal.aborted) return;
        revokeOwned();
        setBlobPreviewUrl(null);
        setBlobLoading(false);
        setBlobDecoded(true);
      });

    return () => {
      ac.abort();
      setBlobLoading(false);
      revokeOwned();
    };
  }, [trimmed]);

  const onDragStart = useCallback(
    (e: React.DragEvent) => {
      if (!trimmed) return;
      if (isRemote && !effectiveSite) {
        dispatch(
          showSystemNotification({
            message: 'Cannot drag remote image: no active site. Open a project in Studio.'
          })
        );
        e.preventDefault();
        return;
      }

      removeGhostRef.current?.();
      const cardTitle = (alt ?? '').trim() || fileNameFromUrl(trimmed);
      const useBlob = Boolean(blobPreviewUrl && blobDecoded);
      const removeGhost = attachRepresentativeDragCard(e, cardTitle, imgRef.current, blobImgRef.current, useBlob);
      removeGhostRef.current = typeof removeGhost === 'function' ? removeGhost : null;

      dispatch(setPreviewEditMode({ editMode: true }));
      const asset = searchItemFromImageSrc(trimmed, alt ?? undefined);
      const previewBase =
        typeof window !== 'undefined' && (window as unknown as { authoring?: { previewAppBaseUri?: string } }).authoring
          ?.previewAppBaseUri;
      if (previewBase && trimmed.startsWith('/')) {
        asset.previewUrl = `${previewBase}${trimmed}?${Date.now()}`;
      }
      getHostToGuestBus().next(assetDragStarted({ asset }));
    },
    [trimmed, isRemote, effectiveSite, alt, dispatch, blobPreviewUrl, blobDecoded]
  );

  const onDragEnd = useCallback(() => {
    removeGhostRef.current?.();
    removeGhostRef.current = null;
    getHostToGuestBus().next(assetDragEnded());
  }, []);

  if (!trimmed) return null;

  const canDrag = !isRemote || Boolean(effectiveSite);
  const caption = isRemote
    ? 'Drag onto an image field in preview (imports when you drop)'
    : 'Drag into preview to place (like Assets panel)';

  const showBlobDisplay = Boolean(blobPreviewUrl && blobDecoded);
  const displaySrc = showBlobDisplay ? blobPreviewUrl! : trimmed;

  return (
    <Box
      sx={{
        my: 1,
        display: 'inline-block',
        maxWidth: '100%',
        position: 'relative',
        verticalAlign: 'top',
        borderRadius: 1,
        border: `1px solid ${theme.palette.mode === 'dark' ? theme.palette.grey[700] : theme.palette.grey[300]}`,
        overflow: 'hidden',
        bgcolor: theme.palette.mode === 'dark' ? theme.palette.grey[900] : theme.palette.grey[50]
      }}
    >
      <img
        ref={(el) => {
          imgRef.current = el;
          blobImgRef.current = showBlobDisplay ? el : null;
        }}
        src={displaySrc}
        alt={alt ?? ''}
        loading="lazy"
        draggable={canDrag}
        onDragStart={onDragStart}
        onDragEnd={onDragEnd}
        style={{
          display: 'block',
          maxWidth: '100%',
          height: 'auto',
          maxHeight: 320,
          cursor: canDrag ? 'grab' : 'default',
          opacity: 1
        }}
      />
      {blobLoading && !showBlobDisplay && (
        <Box
          sx={{
            position: 'absolute',
            inset: 0,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            bgcolor: 'rgba(0,0,0,0.28)',
            pointerEvents: 'none'
          }}
        >
          <CircularProgress size={28} sx={{ color: '#fff' }} />
        </Box>
      )}
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 0.5,
          px: 1,
          py: 0.5,
          borderTop: `1px solid ${theme.palette.mode === 'dark' ? theme.palette.grey[700] : theme.palette.grey[300]}`,
          bgcolor: theme.palette.mode === 'dark' ? theme.palette.grey[800] : theme.palette.grey[100]
        }}
      >
        <DragIndicatorRounded sx={{ fontSize: 18, opacity: 0.7, color: 'text.secondary' }} />
        <Typography variant="caption" color="text.secondary" sx={{ userSelect: 'none' }}>
          {blobLoading ? 'Preparing drag image preview...' : caption}
        </Typography>
      </Box>
    </Box>
  );
}
