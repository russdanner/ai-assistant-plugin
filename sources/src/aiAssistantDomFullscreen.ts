import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Browser Fullscreen API for a DOM subtree (e.g. the whole Project Tools shell).
 * May fail silently if Studio embeds the tool in a context that disallows fullscreen.
 */
export function useDomFullscreen<T extends HTMLElement = HTMLDivElement>() {
  const ref = useRef<T | null>(null);
  const [isFullscreen, setIsFullscreen] = useState(false);

  useEffect(() => {
    const sync = () => {
      const el = ref.current;
      setIsFullscreen(Boolean(el && document.fullscreenElement === el));
    };
    document.addEventListener('fullscreenchange', sync);
    return () => document.removeEventListener('fullscreenchange', sync);
  }, []);

  const toggleFullscreen = useCallback(() => {
    const el = ref.current;
    if (!el) return;
    void (async () => {
      try {
        if (document.fullscreenElement === el) {
          await document.exitFullscreen();
        } else {
          await el.requestFullscreen();
        }
      } catch {
        // Embedded Studio / iframe policies may reject fullscreen.
      }
    })();
  }, []);

  return { ref, isFullscreen, toggleFullscreen };
}
