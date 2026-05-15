/**
 * Live snapshot from the legacy form control (`control/ai-assistant/main.js` → `getAuthoringFormContext`).
 *
 * **XB / ICE chat** does not use this type — preview hooks + `streamChat` `contentPath` only.
 * **Form-engine assistant** only — keep accordion/UI in `AiAssistantFormControlPanel.tsx`; do not mix chat/orchestration logic there.
 */
export type AssistantFieldApplyResult = {
  applied: string[];
  skipped: string[];
  errors?: string[];
  error?: string;
};

export type AuthoringFormContextSnapshot = {
  contentTypeId?: string;
  definitionXml?: string;
  fieldValuesJson?: string;
  /**
   * Live content item XML from `CStudioForms.Util.serializeModelToXml(form, false)` — same shape as Save.
   */
  serializedContentXml?: string;
  /** `form.path` — item being edited; used for CONTENT: macro vs live model + appendix. */
  contentPath?: string;
  /**
   * Apply flat `fieldId → string` map from assistant JSON (`aiassistantFormFieldUpdates`); updates model + controls + validation.
   */
  applyAssistantFieldUpdates?: (updates: Record<string, string>) => AssistantFieldApplyResult;
};
