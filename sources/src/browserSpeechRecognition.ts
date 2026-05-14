/**
 * Browser Web Speech API (SpeechRecognition). Chrome/Edge typically; Safari may use webkit prefix.
 */
export type SpeechRecognitionClass = new () => SpeechRecognition;

export function getSpeechRecognitionCtor(): SpeechRecognitionClass | null {
  if (typeof window === 'undefined') return null;
  const w = window as typeof window & {
    SpeechRecognition?: SpeechRecognitionClass;
    webkitSpeechRecognition?: SpeechRecognitionClass;
  };
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null;
}
