const { jsx, jsxs, Fragment } = craftercms.libs?.reactJsxRuntime;
const { useTheme, Box, CircularProgress, Typography, Stack, Button, Divider, Paper, TextField, IconButton, Popover, paperClasses, Tooltip } = craftercms.libs.MaterialUI;
const { useState, useRef, useEffect, useMemo } = craftercms.libs.React;
const MinimizedBar = craftercms.components.MinimizedBar && Object.prototype.hasOwnProperty.call(craftercms.components.MinimizedBar, 'default') ? craftercms.components.MinimizedBar['default'] : craftercms.components.MinimizedBar;
const DialogHeader = craftercms.components.DialogHeader && Object.prototype.hasOwnProperty.call(craftercms.components.DialogHeader, 'default') ? craftercms.components.DialogHeader['default'] : craftercms.components.DialogHeader;
const AlertDialog = craftercms.components.AlertDialog && Object.prototype.hasOwnProperty.call(craftercms.components.AlertDialog, 'default') ? craftercms.components.AlertDialog['default'] : craftercms.components.AlertDialog;
const PrimaryButton = craftercms.components.PrimaryButton && Object.prototype.hasOwnProperty.call(craftercms.components.PrimaryButton, 'default') ? craftercms.components.PrimaryButton['default'] : craftercms.components.PrimaryButton;
const SecondaryButton = craftercms.components.SecondaryButton && Object.prototype.hasOwnProperty.call(craftercms.components.SecondaryButton, 'default') ? craftercms.components.SecondaryButton['default'] : craftercms.components.SecondaryButton;
const SendRounded = craftercms.utils.constants.components.get('@mui/icons-material/SendRounded') && Object.prototype.hasOwnProperty.call(craftercms.utils.constants.components.get('@mui/icons-material/SendRounded'), 'default') ? craftercms.utils.constants.components.get('@mui/icons-material/SendRounded')['default'] : craftercms.utils.constants.components.get('@mui/icons-material/SendRounded');
const { createSvgIcon } = craftercms.libs.MaterialUI;
const ToolsPanelListItemButton = craftercms.components.ToolsPanelListItemButton && Object.prototype.hasOwnProperty.call(craftercms.components.ToolsPanelListItemButton, 'default') ? craftercms.components.ToolsPanelListItemButton['default'] : craftercms.components.ToolsPanelListItemButton;
const { useSelector } = craftercms.libs.ReactRedux;
const { getGuestToHostBus, getHostToHostBus, getHostToGuestBus } = craftercms.utils.subjects;
const { merge } = craftercms.libs.rxjs;

const CRAFTERQ_CHAT_USER_HEADER = 'X-CrafterQ-Chat-User';
const CRAFTERQ_CHAT_USER_STORAGE_KEY = 'crafterq.chatUser';
function getStoredChatUser() {
    try {
        return window.localStorage.getItem(CRAFTERQ_CHAT_USER_STORAGE_KEY);
    }
    catch {
        return null;
    }
}
function setStoredChatUser(value) {
    try {
        if (!value)
            return;
        const trimmed = value.trim();
        if (trimmed)
            window.localStorage.setItem(CRAFTERQ_CHAT_USER_STORAGE_KEY, trimmed);
    }
    catch {
        // ignore
    }
}
async function fetchChatConfig(agentId) {
    const token = getStoredChatUser();
    const res = await fetch(`https://api.crafterq.ai/v1/agents/${agentId}/chat_config`, {
        mode: 'cors',
        credentials: 'include',
        headers: token ? { [CRAFTERQ_CHAT_USER_HEADER]: token } : undefined
    });
    const headerToken = res.headers.get(CRAFTERQ_CHAT_USER_HEADER);
    if (headerToken)
        setStoredChatUser(headerToken);
    if (!res.ok) {
        const text = await res.text().catch(() => '');
        throw new Error(`CrafterQ chat_config failed (${res.status}): ${text || res.statusText}`);
    }
    const json = (await res.json());
    return json.config;
}
/**
 * Streams chat responses via SSE (text/event-stream) using fetch streaming.
 * This mirrors the widget behavior: POST /v1/chats?stream=true&agentId=...
 */
async function streamChat(args) {
    const { agentId, prompt, chatId, signal, onMessage } = args;
    const url = new URL('https://api.crafterq.ai/v1/chats');
    url.searchParams.set('stream', 'true');
    url.searchParams.set('agentId', agentId);
    const token = getStoredChatUser();
    const headers = { 'Content-Type': 'application/json' };
    if (token)
        headers[CRAFTERQ_CHAT_USER_HEADER] = token;
    const res = await fetch(url.toString(), {
        method: 'POST',
        mode: 'cors',
        credentials: 'include',
        headers,
        body: JSON.stringify(chatId ? { prompt, chatId } : { prompt }),
        signal
    });
    if (!res.ok) {
        const text = await res.text().catch(() => '');
        throw new Error(`CrafterQ streamChat failed (${res.status}): ${text || res.statusText}`);
    }
    const reader = res.body?.getReader();
    if (!reader)
        throw new Error('CrafterQ streamChat: no response body reader available');
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    while (true) {
        const { value, done } = await reader.read();
        if (done)
            break;
        buffer += decoder.decode(value, { stream: true });
        // SSE frames are separated by a blank line.
        // We only need `data:` lines for this widget.
        let frameEnd;
        while ((frameEnd = buffer.indexOf('\n\n')) !== -1) {
            const frame = buffer.slice(0, frameEnd);
            buffer = buffer.slice(frameEnd + 2);
            const lines = frame.split('\n');
            for (const rawLine of lines) {
                const line = rawLine.trimEnd();
                if (!line.startsWith('data:'))
                    continue;
                const data = line.slice('data:'.length).trim();
                if (!data)
                    continue;
                try {
                    onMessage(JSON.parse(data));
                }
                catch {
                    // ignore malformed chunks
                }
            }
        }
    }
}

function CrafterQChat(props) {
    const theme = useTheme();
    const { agentId, initialMessages } = props;
    const [loading, setLoading] = useState(true);
    const [configError, setConfigError] = useState(null);
    const [promptPlaceholder, setPromptPlaceholder] = useState('Ask CrafterQ…');
    const [quickMessages, setQuickMessages] = useState([]);
    const [welcomeMessage, setWelcomeMessage] = useState(null);
    const [chatId, setChatId] = useState(undefined);
    const [messages, setMessages] = useState(() => {
        const base = [];
        (initialMessages ?? []).forEach((m, idx) => {
            base.push({
                id: `initial-${idx}`,
                role: (m.role || '').toLowerCase() === 'assistant' ? 'assistant' : 'user',
                text: m.content ?? ''
            });
        });
        return base;
    });
    const [draft, setDraft] = useState('');
    const [sending, setSending] = useState(false);
    const abortRef = useRef(null);
    const scrollRef = useRef(null);
    useEffect(() => {
        let active = true;
        setLoading(true);
        setConfigError(null);
        fetchChatConfig(agentId)
            .then((cfg) => {
            if (!active)
                return;
            setPromptPlaceholder(cfg.promptFieldPlaceholder || 'Ask CrafterQ…');
            setQuickMessages(Array.isArray(cfg.quickMessages) ? cfg.quickMessages.filter(Boolean) : []);
            setWelcomeMessage(typeof cfg.welcomeMessage === 'string' ? cfg.welcomeMessage : null);
            if (cfg.welcomeMessage && messages.length === 0) {
                setMessages([
                    {
                        id: 'welcome',
                        role: 'assistant',
                        text: cfg.welcomeMessage
                    }
                ]);
            }
        })
            .catch((e) => {
            if (!active)
                return;
            setConfigError(e instanceof Error ? e.message : String(e));
        })
            .finally(() => {
            if (!active)
                return;
            setLoading(false);
        });
        return () => {
            active = false;
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [agentId]);
    useEffect(() => {
        // Keep scrolled to bottom
        const el = scrollRef.current;
        if (!el)
            return;
        el.scrollTop = el.scrollHeight;
    }, [messages, sending]);
    const canSend = useMemo(() => draft.trim().length > 0 && !sending, [draft, sending]);
    const startSend = async (prompt) => {
        const trimmed = prompt.trim();
        if (!trimmed)
            return;
        abortRef.current?.abort();
        const ac = new AbortController();
        abortRef.current = ac;
        const userId = `user-${Date.now()}`;
        const assistantId = `assistant-${Date.now()}`;
        setMessages((prev) => [
            ...prev,
            { id: userId, role: 'user', text: trimmed },
            { id: assistantId, role: 'assistant', text: '', isStreaming: true }
        ]);
        setDraft('');
        setSending(true);
        let streamingMessageId;
        try {
            await streamChat({
                agentId,
                chatId,
                prompt: trimmed,
                signal: ac.signal,
                onMessage: (evt) => {
                    const evtChatId = evt.metadata?.chatId;
                    if (evtChatId && !chatId)
                        setChatId(evtChatId);
                    const evtMsgId = evt.metadata?.messageId;
                    if (evtMsgId && !streamingMessageId)
                        streamingMessageId = evtMsgId;
                    const textChunk = evt.text ?? '';
                    const isCompleted = Boolean(evt.metadata?.completed);
                    if (textChunk) {
                        setMessages((prev) => prev.map((m) => {
                            if (m.id !== assistantId)
                                return m;
                            return { ...m, text: (m.text || '') + textChunk };
                        }));
                    }
                    if (isCompleted) {
                        setMessages((prev) => prev.map((m) => {
                            if (m.id !== assistantId)
                                return m;
                            return { ...m, isStreaming: false };
                        }));
                    }
                }
            });
        }
        catch (e) {
            const errText = e instanceof Error ? e.message : String(e);
            setMessages((prev) => prev.map((m) => {
                if (m.id !== assistantId)
                    return m;
                return { ...m, text: `Error: ${errText}`, isStreaming: false };
            }));
        }
        finally {
            setSending(false);
        }
    };
    if (loading) {
        return (jsx(Box, { sx: { flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }, children: jsx(CircularProgress, { size: 28 }) }));
    }
    if (configError) {
        return (jsxs(Box, { sx: { p: 2 }, children: [jsx(Typography, { variant: "subtitle1", sx: { fontWeight: 600 }, children: "CrafterQ unavailable" }), jsx(Typography, { variant: "body2", color: "text.secondary", children: configError })] }));
    }
    return (jsxs(Box, { sx: { display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }, children: [quickMessages.length > 0 && (jsx(Box, { sx: { p: 1.5 }, children: jsx(Stack, { direction: "row", spacing: 1, sx: { flexWrap: 'wrap' }, children: quickMessages.slice(0, 6).map((qm) => (jsx(Button, { size: "small", variant: "outlined", onClick: () => startSend(qm), disabled: sending, sx: { textTransform: 'none' }, children: qm }, qm))) }) })), jsx(Divider, {}), jsx(Box, { ref: scrollRef, sx: {
                    flex: 1,
                    overflow: 'auto',
                    p: 2,
                    background: theme.palette.mode === 'dark' ? theme.palette.grey[900] : theme.palette.grey[50]
                }, children: jsx(Stack, { spacing: 1.25, children: messages.map((m) => (jsx(Box, { sx: {
                            display: 'flex',
                            justifyContent: m.role === 'user' ? 'flex-end' : 'flex-start'
                        }, children: jsxs(Paper, { elevation: 0, sx: {
                                px: 1.5,
                                py: 1,
                                maxWidth: '80%',
                                borderRadius: 2,
                                backgroundColor: m.role === 'user'
                                    ? theme.palette.primary.main
                                    : theme.palette.mode === 'dark'
                                        ? theme.palette.grey[800]
                                        : '#fff',
                                color: m.role === 'user' ? theme.palette.primary.contrastText : theme.palette.text.primary,
                                border: m.role === 'user'
                                    ? 'none'
                                    : `1px solid ${theme.palette.mode === 'dark' ? theme.palette.grey[700] : theme.palette.grey[200]}`
                            }, children: [jsx(Typography, { variant: "body2", sx: { whiteSpace: 'pre-wrap' }, children: m.text }), m.isStreaming && (jsx(Typography, { variant: "caption", sx: { display: 'block', mt: 0.5, opacity: 0.7 }, children: "typing\u2026" }))] }) }, m.id))) }) }), jsx(Divider, {}), jsx(Box, { sx: { p: 1.5 }, children: jsxs(Stack, { direction: "row", spacing: 1, alignItems: "center", children: [jsx(TextField, { fullWidth: true, size: "small", placeholder: promptPlaceholder, value: draft, onChange: (e) => setDraft(e.target.value), onKeyDown: (e) => {
                                if (e.key === 'Enter' && !e.shiftKey) {
                                    e.preventDefault();
                                    if (canSend)
                                        startSend(draft);
                                }
                            }, disabled: sending }), jsx(IconButton, { color: "primary", onClick: () => startSend(draft), disabled: !canSend, "aria-label": "Send", children: jsx(SendRounded, {}) })] }) })] }));
}

function CrafterQPopover(props) {
    const theme = useTheme();
    const { open, onClose, isMinimized = false, onMinimize, onMaximize, appBarTitle = 'CrafterQ Authoring Assistant', width = 492, height = 595, hideBackdrop, enableCustomModel = true, agentId = '019c7237-478b-7f98-9a5c-87144c3fb010', ...popoverProps } = props;
    const [openAlertDialog, setOpenAlertDialog] = useState(false);
    return (jsxs(Fragment, { children: [jsxs(Popover, { open: open && !isMinimized, onClose: (e, reason) => {
                    // if (CrafterQRef.current?.hasConversation()) {
                    //   onMinimize?.();
                    // } else {
                    //   onClose(e, reason);
                    // }
                }, keepMounted: isMinimized, anchorReference: "none", anchorOrigin: { vertical: 'bottom', horizontal: 'right' }, anchorPosition: { top: 100, left: 100 }, BackdropProps: {
                    invisible: true,
                    sx: { backgroundColor: 'rgba(0, 0, 0, 0.5)' }
                }, ...popoverProps, sx: {
                    [`> .${paperClasses.root}`]: {
                        width: '817px',
                        height: '90%',
                        display: 'flex',
                        flexDirection: 'column',
                        bottom: 10,
                        right: 10
                    },
                    ...popoverProps?.sx
                }, children: [jsx(DialogHeader, { title: "", sxs: {
                            root: { boxShadow: theme.shadows[4], borderBottom: 'none' },
                            subtitleWrapper: {
                                width: '100%'
                            }
                        }, onMinimizeButtonClick: () => onMinimize?.(), onCloseButtonClick: (e) => onClose(e, null) }), jsx(CrafterQChat, { agentId: agentId })] }), jsx(MinimizedBar, { open: isMinimized, onMaximize: onMaximize, title: appBarTitle }), jsx(AlertDialog, { disableBackdropClick: true, disableEscapeKeyDown: true, open: openAlertDialog, title: "Close this chat?", body: "The current conversation will be lost.", buttons: jsxs(Fragment, { children: [jsx(PrimaryButton, { onClick: (e) => {
                                setOpenAlertDialog(false);
                                onClose(e, null);
                            }, autoFocus: true, fullWidth: true, size: "large", children: "Close" }), jsx(SecondaryButton, { onClick: () => {
                                setOpenAlertDialog(false);
                                onMinimize?.();
                            }, fullWidth: true, size: "large", children: "Minimize" }), jsx(SecondaryButton, { onClick: () => setOpenAlertDialog(false), fullWidth: true, size: "large", children: "Cancel" })] }) })] }));
}

const logoWidgetId = 'craftercms.components.aiassistant.OpenAILogo';
const popoverWidgetId = 'craftercms.components.aiassistant.CrafterQPopover';
const CrafterQClosedMessageId = 'craftercms.aiassistant.CrafterQClosed';
const helperWidgetId = 'craftercms.components.aiassistant.Helper';
/*
import { EmptyStateOption } from './CrafterQ';

export const CrafterQResultMessageId = 'craftercms.aiassistant.CrafterQResult';

// Default CrafterQ models
export const defaultChatModel = 'gpt-4o';
export const defaultImageModel = 'gpt-image-1';
export const defaultOpenAiImageSize = '1024x1024';

// Lanaguge codes for speech to text
export const languageCodes = [
  { code: 'en-US', label: 'English (United States)' },
  { code: 'en-GB', label: 'English (United Kingdom)' },
  { code: 'en-CA', label: 'English (Canada)' },
  { code: 'en-AU', label: 'English (Australia)' },
  { code: 'fr-FR', label: 'French (France)' },
  { code: 'fr-CA', label: 'French (Canada)' },
  { code: 'fr-BE', label: 'French (Belgium)' },
  { code: 'fr-CH', label: 'French (Switzerland)' },
  { code: 'es-ES', label: 'Spanish (Spain)' },
  { code: 'es-MX', label: 'Spanish (Mexico)' },
  { code: 'es-AR', label: 'Spanish (Argentina)' },
  { code: 'es-CO', label: 'Spanish (Colombia)' },
  { code: 'de-DE', label: 'German (Germany)' },
  { code: 'de-AT', label: 'German (Austria)' },
  { code: 'de-CH', label: 'German (Switzerland)' },
  { code: 'pt-PT', label: 'Portuguese (Portugal)' },
  { code: 'pt-BR', label: 'Portuguese (Brazil)' },
  { code: 'zh-CN', label: 'Chinese (Simplified, China)' },
  { code: 'zh-TW', label: 'Chinese (Traditional, Taiwan)' },
  { code: 'zh-HK', label: 'Chinese (Traditional, Hong Kong)' },
  { code: 'ja-JP', label: 'Japanese (Japan)' },
  { code: 'ko-KR', label: 'Korean (South Korea)' },
  { code: 'ru-RU', label: 'Russian (Russia)' },
  { code: 'ar-SA', label: 'Arabic (Saudi Arabia)' },
  { code: 'ar-AE', label: 'Arabic (United Arab Emirates)' },
  { code: 'it-IT', label: 'Italian (Italy)' },
  { code: 'it-CH', label: 'Italian (Switzerland)' },
  { code: 'nl-NL', label: 'Dutch (Netherlands)' },
  { code: 'nl-BE', label: 'Dutch (Belgium)' },
  { code: 'sv-SE', label: 'Swedish (Sweden)' },
  { code: 'sv-FI', label: 'Swedish (Finland)' },
  { code: 'no-NO', label: 'Norwegian (Norway)' },
  { code: 'da-DK', label: 'Danish (Denmark)' },
  { code: 'fi-FI', label: 'Finnish (Finland)' },
  { code: 'pl-PL', label: 'Polish (Poland)' },
  { code: 'cs-CZ', label: 'Czech (Czech Republic)' },
  { code: 'sk-SK', label: 'Slovak (Slovakia)' },
  { code: 'hu-HU', label: 'Hungarian (Hungary)' },
  { code: 'el-GR', label: 'Greek (Greece)' },
  { code: 'he-IL', label: 'Hebrew (Israel)' },
  { code: 'tr-TR', label: 'Turkish (Turkey)' },
  { code: 'th-TH', label: 'Thai (Thailand)' },
  { code: 'vi-VN', label: 'Vietnamese (Vietnam)' },
  { code: 'id-ID', label: 'Indonesian (Indonesia)' },
  { code: 'ms-MY', label: 'Malay (Malaysia)' },
  { code: 'hi-IN', label: 'Hindi (India)' },
  { code: 'ta-IN', label: 'Tamil (India)' },
  { code: 'te-IN', label: 'Telugu (India)' },
  { code: 'ur-PK', label: 'Urdu (Pakistan)' },
  { code: 'fa-IR', label: 'Persian (Iran)' },
  { code: 'uk-UA', label: 'Ukrainian (Ukraine)' },
  { code: 'ro-RO', label: 'Romanian (Romania)' },
  { code: 'bg-BG', label: 'Bulgarian (Bulgaria)' },
  { code: 'hr-HR', label: 'Croatian (Croatia)' },
  { code: 'sr-RS', label: 'Serbian (Serbia)' },
  { code: 'sl-SI', label: 'Slovenian (Slovenia)' },
  { code: 'lv-LV', label: 'Latvian (Latvia)' },
  { code: 'lt-LT', label: 'Lithuanian (Lithuania)' },
  { code: 'et-EE', label: 'Estonian (Estonia)' }
];

export const copyCodeSvg = `
<svg width="16" height="16" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" class="icon-sm"><path fill-rule="evenodd" clip-rule="evenodd" d="M7 5C7 3.34315 8.34315 2 10 2H19C20.6569 2 22 3.34315 22 5V14C22 15.6569 20.6569 17 19 17H17V19C17 20.6569 15.6569 22 14 22H5C3.34315 22 2 20.6569 2 19V10C2 8.34315 3.34315 7 5 7H7V5ZM9 7H14C15.6569 7 17 8.34315 17 10V15H19C19.5523 15 20 14.5523 20 14V5C20 4.44772 19.5523 4 19 4H10C9.44772 4 9 4.44772 9 5V7ZM5 9C4.44772 9 4 9.44772 4 10V19C4 19.5523 4.44772 20 5 20H14C14.5523 20 15 19.5523 15 19V10C15 9.44772 14.5523 9 14 9H5Z" fill="currentColor"></path></svg>
`;

export const copiedCodeSvg = `
<svg width="16" height="16" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" class="icon-sm"><path fill-rule="evenodd" clip-rule="evenodd" d="M18.0633 5.67387C18.5196 5.98499 18.6374 6.60712 18.3262 7.06343L10.8262 18.0634C10.6585 18.3095 10.3898 18.4679 10.0934 18.4957C9.79688 18.5235 9.50345 18.4178 9.29289 18.2072L4.79289 13.7072C4.40237 13.3167 4.40237 12.6835 4.79289 12.293C5.18342 11.9025 5.81658 11.9025 6.20711 12.293L9.85368 15.9396L16.6738 5.93676C16.9849 5.48045 17.607 5.36275 18.0633 5.67387Z" fill="currentColor"></path></svg>
`;

// Function call definitions for CrafterQ
export const functionTools = [
  // {
  //   type: 'function',
  //   function: {
  //     name: 'publish_content',
  //     description:
  //       'Triggers a content publish action in CrafterCMS for a specific path at a specified date and time. If no currentContent or path or name parameters are available. Ask user what content to publish.',
  //     parameters: {
  //       type: 'object',
  //       properties: {
  //         internalName: {
  //           type: 'string',
  //           description:
  //             "Content identifier name. This usually is the page title, internal name. For example: 'Home', 'Categories', 'Search Results', or any specific names."
  //         },
  //         currentContent: {
  //           type: 'boolean',
  //           description:
  //             "A flag which is true if the publishing path is the 'current previewing page', 'current content', or terms such as 'this content', 'this component'."
  //         },
  //         path: {
  //           type: 'string',
  //           description: "The path in CrafterCMS where the content resides. For example, '/site/website/index.xml'."
  //         },
  //         date: {
  //           type: 'string',
  //           description:
  //             "The scheduled date and time to publish the content in ISO 8601 format. For example, '2025-12-12T00:00:00Z'."
  //         },
  //         publishingTarget: {
  //           type: 'string',
  //           description:
  //             "The publishing target or environment. Possible values are 'live' or 'staging'. Default if not specified is 'live'."
  //         }
  //       },
  //       additionalProperties: false
  //     }
  //   }
  // },
  // {
  //   type: 'function',
  //   function: {
  //     name: 'analyze_template',
  //     description:
  //       'CrafterCMS allows developers to model the content as general reusable items, and fold those into pages. Pages aggregate content from components as needed and are associated with a FreeMarker template that can render the final page. This function triggers a template analyzing action in CrafterCMS for a specific path or the current previewing page. If no currentContent or path or name parameters are available. Ask user what template to update. If analyzing currentContent template, the function will resolve the template path from the current page.',
  //     parameters: {
  //       type: 'object',
  //       properties: {
  //         instructions: {
  //           type: 'string',
  //           description: 'Instructions for analyzing the template of a page or a component'
  //         },
  //         currentContent: {
  //           type: 'boolean',
  //           description:
  //             "A flag which is true if the content path is the 'current previewing page', 'current content', 'previewing page', or terms such as 'this content', 'this page', 'this component'."
  //         },
  //         templatePath: {
  //           type: 'string',
  //           description:
  //             "The path in CrafterCMS where the template resides. For example, '/templates/web/pages/home.ftl'."
  //         },
  //         contentPath: {
  //           type: 'string',
  //           description:
  //             "The path in CrafterCMS where the content resides. For example, '/site/website/index.xml'. This path is used to resolve the template path using this function"
  //         }
  //       },
  //       required: ['instructions'],
  //       additionalProperties: false
  //     }
  //   }
  // },
  {
    type: 'function',
    function: {
      name: 'update_template',
      description:
        'CrafterCMS allows developers to model the content as general reusable items, and fold those into pages. Pages aggregate content from components as needed and are associated with a FreeMarker template that can render the final page. This function triggers a template update action in CrafterCMS for a specific path or the current previewing page. If no currentContent or path or name parameters are available. Ask user what template to update. If updating currentContent template, the function will resolve the template path from the current page.',
      parameters: {
        type: 'object',
        properties: {
          instructions: {
            type: 'string',
            description: 'Instructions for updating the template of a page or a component'
          },
          currentContent: {
            type: 'boolean',
            description:
              "A flag which is true if the content path is the 'current previewing page', 'current content', 'previewing page', or terms such as 'this content', 'this page', 'this component'."
          },
          templatePath: {
            type: 'string',
            description:
              "The path in CrafterCMS where the template resides. For example, '/templates/web/pages/home.ftl'."
          },
          contentPath: {
            type: 'string',
            description:
              "The path in CrafterCMS where the content resides. For example, '/site/website/index.xml'. This path is used to resolve the template path using this function"
          },
          contentType: {
            type: 'string',
            description:
              "The content type to be updated the model definition. The content type is a string start with either '/page' or '/component'. For example, updating the content type '/page/home' would result in updating the file '/config/studio/content-types/page/home/form-definition.xml'"
          }
        },
        required: ['instructions'],
        additionalProperties: false
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'update_content',
      description:
        "Update a page or component. Pages are top-level container types. Pages hold content, and optionally components. Content within pages is made up of various types, for example content can be a date, an image, or a rich text field. Components only differ from pages in that they can't render by themselves, instead, they must render within a container page or another component. The page or component path usually start with '/site/webiste', '/site/components' or '/site/taxonomy'. The content file name is XML and has .xml extension.",
      parameters: {
        type: 'object',
        properties: {
          instructions: {
            type: 'string',
            description: 'Instructions for updating the content'
          },
          currentContent: {
            type: 'boolean',
            description:
              "A flag which is true if the content path is the 'current previewing page', 'current content', 'previewing page', or terms such as 'this content', 'this page', 'this component'."
          },
          contentPath: {
            type: 'string',
            description: "The path in CrafterCMS where the content resides. For example, '/site/website/index.xml'"
          }
        },
        required: ['instructions'],
        additionalProperties: false
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'update_content_type',
      description:
        "Every content object in CrafterCMS is an object associated with a Content Model. Content Models allow you to add structure to your content and facilitate consumption via various visual representations or via APIs. Content Types are limited to two core types: Pages and Components. The content model is the content pieces that will be captured from the content authors for the page or component. Content type model is defined using the file 'form-definition.xml'. For example, the content model definition file for the content type '/page/home' is located at '/config/studio/content-types/page/home/form-definition.xml'. This function triggers an update to a content model definition to includes new fields, modify existing fields.",
      parameters: {
        type: 'object',
        properties: {
          instructions: {
            type: 'string',
            description: 'Instructions for updating the content model'
          },
          currentContent: {
            type: 'boolean',
            description:
              "A flag which is true if the content path is the 'current previewing page', 'current content', 'previewing page', or terms such as 'this content', 'this page', 'this component'."
          },
          contentType: {
            type: 'string',
            description:
              "The content type to be updated the model definition. The content type is a string start with either '/page' or '/component'. For example, updating the content type '/page/home' would result in updating the file '/config/studio/content-types/page/home/form-definition.xml'"
          }
        },
        required: ['instructions'],
        additionalProperties: false
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'revert_change',
      description:
        'Reverts or rollbacks content update to a previous version in CrafterCMS. If no `path` is provided and `currentContent` is used, make sure to ask the user what is the `revertType` in the case `revertType` is not provided.',
      parameters: {
        type: 'object',
        properties: {
          path: {
            type: 'string',
            description: 'The path of the content to revert.'
          },
          currentContent: {
            type: 'boolean',
            description:
              "A flag which is true if the content path is the 'current previewing page', 'current content', 'previewing page', or terms such as 'this content', 'this page', 'this component'."
          },
          revertType: {
            type: 'string',
            description:
              'If currentContent is true. This parameter is required to know that kind of data to revert. The possible values are: content, template, contentType'
          }
        },
        additionalProperties: false
      }
    }
  }
];

// Default prompt options for chat mode
export const CrafterQEmptyStateOptionsChat: Array<EmptyStateOption> = [
  {
    id: 'useCasualTone',
    title: 'Set a casual tone for the AI content',
    subheader: 'e.g. Ready to chat about anything you like!',
    messages: [
      {
        role: 'system',
        content:
          'Answer upcoming questions using casual, informal language to convey a casual conversation with a real person. Confirm and ask the user for a prompt to begin working'
      }
    ]
  },
  {
    id: 'useProfessionalTone',
    title: 'Set a formal tone for the AI content',
    subheader: 'e.g. How may I be of assistance to you today?',
    messages: [
      {
        role: 'system',
        content:
          'Answers upcoming questions using polished, formal, and respectful language to convey professional expertise and competence. Acknowledge and ask the user for a prompt to begin working'
      }
    ]
  },
  {
    id: 'generateTitle',
    title: 'Suggest title for your content',
    prompt: 'Suggest a title for an article. Topic: '
  },
  {
    id: 'generateBody',
    title: 'Generate a body for your an article',
    prompt: 'Write the body for an article. Topic: '
  }
];

// Default prompt options for image generating mode
export const emptyStateOptionsGenerateImages: Array<EmptyStateOption> = [
  {
    id: 'generateCasualImage',
    title: 'Create an image with a casual vibe',
    subheader: 'e.g. Design a fun, relaxed scene!',
    prompt: 'Generate an image with a casual, informal theme. Include this text in the design: '
  },
  {
    id: 'generateFormalImage',
    title: 'Create an image with a professional tone',
    subheader: 'e.g. Depict a sleek, corporate environment',
    prompt: 'Generate an image with a polished, formal theme. Include this text in the design: '
  },
  {
    id: 'generateTitleImage',
    title: 'Incorporate a title into your image',
    prompt: 'Create an image based on a title. Title: '
  },
  {
    id: 'generateBodyImage',
    title: 'Incorporate a body of text into your image',
    prompt: 'Generate an image based on an article body text concept. Concept: '
  }
];
*/

var CrafterQLogo = createSvgIcon(jsxs("svg", { width: "32", height: "33", viewBox: "0 0 32 33", fill: "none", xmlns: "http://www.w3.org/2000/svg", children: [jsx("path", { d: "M25.5147 22.3719C25.4685 22.3097 25.2835 22.2942 25.1911 22.3719C23.727 23.6458 21.631 24.3915 19.5967 24.3915C15.0503 24.3915 11.4748 20.7561 11.3515 16.0642C11.4748 11.3724 15.0503 7.73693 19.5967 7.73693C21.6464 7.73693 23.7424 8.4982 25.1911 9.75661C25.2835 9.83429 25.4685 9.81876 25.5147 9.75661L28.597 6.44744C28.6587 6.3853 28.6587 6.18333 28.5662 6.09011C27.5336 5.08027 26.3315 4.25687 25.0369 3.63543L24.6979 3.52667L24.7595 0.69912C23.8811 0.403936 23.0026 0.15536 22.0933 0L20.7525 2.43915L20.4289 2.40808C19.5504 2.3304 18.934 2.3304 18.0555 2.40808L17.7319 2.43915L16.3911 0C15.4972 0.15536 14.6033 0.403936 13.7403 0.714656L13.7865 3.4956L13.4937 3.63543C12.8002 3.96168 12.1067 4.36562 11.444 4.8317L11.182 5.01813L8.80862 3.58882C8.09969 4.17919 7.4524 4.84723 6.86676 5.54635L8.28462 7.9389L8.09969 8.20301C7.83769 8.57588 7.59111 8.97981 7.3137 9.4925L7.09794 9.91197C7.03629 10.0363 6.97464 10.145 6.913 10.2693L6.77429 10.5645L4.01563 10.5334C3.7074 11.4034 3.46082 12.3045 3.3067 13.2056L5.72631 14.5572L5.69549 14.8835C5.64925 15.334 5.63384 15.7069 5.63384 16.0798C5.63384 16.4526 5.64925 16.8255 5.69549 17.276L5.72631 17.6023L3.3067 18.9539C3.46082 19.855 3.7074 20.7561 4.01563 21.6261L6.77429 21.5795L6.913 21.8747C6.97464 21.999 7.03629 22.1077 7.09794 22.232L7.3137 22.6515C7.59111 23.1642 7.83769 23.5681 8.09969 23.941L8.28462 24.2051L6.86676 26.5976C7.4524 27.2968 8.1151 27.9648 8.80862 28.5552L11.182 27.1259L11.444 27.3123C12.1067 27.7784 12.8002 28.1823 13.4937 28.5086L13.7865 28.6484L13.7403 31.4293C14.6033 31.7401 15.4972 31.9886 16.3911 32.144L17.7319 29.7048L18.0555 29.7359C18.934 29.8136 19.5504 29.8136 20.4289 29.7359L20.7525 29.7048L22.0933 32.144C22.9872 31.9886 23.8811 31.7401 24.7441 31.4293L24.6825 28.6018L25.0215 28.493C26.3161 27.8871 27.5182 27.0482 28.5508 26.0383C28.6432 25.9451 28.6432 25.7432 28.5816 25.681L25.5147 22.3719Z", fill: "#00000033" }), jsx("path", { d: "M18.4327 18.0631H20.6742L21.8011 19.5128L22.9097 20.8041L24.9989 23.4233H22.5381L21.1006 21.6569L20.3636 20.6092L18.4327 18.0631ZM25.1877 16.1627C25.1877 17.5231 24.9299 18.6804 24.4142 19.6346C23.9025 20.5889 23.2041 21.3178 22.3188 21.8214C21.4376 22.3208 20.4468 22.5706 19.3463 22.5706C18.2378 22.5706 17.2429 22.3188 16.3617 21.8153C15.4805 21.3117 14.7841 20.5828 14.2724 19.6286C13.7608 18.6743 13.5049 17.519 13.5049 16.1627C13.5049 14.8023 13.7608 13.645 14.2724 12.6908C14.7841 11.7365 15.4805 11.0096 16.3617 10.5101C17.2429 10.0066 18.2378 9.75482 19.3463 9.75482C20.4468 9.75482 21.4376 10.0066 22.3188 10.5101C23.2041 11.0096 23.9025 11.7365 24.4142 12.6908C24.9299 13.645 25.1877 14.8023 25.1877 16.1627ZM22.5137 16.1627C22.5137 15.2815 22.3818 14.5384 22.1178 13.9333C21.8579 13.3283 21.4904 12.8694 21.0153 12.5567C20.5402 12.2441 19.9839 12.0877 19.3463 12.0877C18.7088 12.0877 18.1525 12.2441 17.6774 12.5567C17.2023 12.8694 16.8327 13.3283 16.5688 13.9333C16.3089 14.5384 16.179 15.2815 16.179 16.1627C16.179 17.0439 16.3089 17.787 16.5688 18.3921C16.8327 18.9971 17.2023 19.456 17.6774 19.7687C18.1525 20.0813 18.7088 20.2377 19.3463 20.2377C19.9839 20.2377 20.5402 20.0813 21.0153 19.7687C21.4904 19.456 21.8579 18.9971 22.1178 18.3921C22.3818 17.787 22.5137 17.0439 22.5137 16.1627Z", fill: "black" })] }), 'crafterq');

/*
 * Copyright (C) 2007-2022 Crafter Software Corporation. All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License version 3 as published by
 * the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

function useActiveUser() {
  return useSelector((state) => state.user);
}

/* Only want a single "Helper" instance listening, but people may use multiple helpers to put
 * the button in multiple places at once. This mechanism would leave the listening off if the
 * active listener unmounts. If this indeed becomes a problem, will revisit the mechanism. */
let helperListening = false;
// const createCrafterQPopoverProps = (user: User, other?: Partial<CrafterQPopoverProps>): Partial<CrafterQPopoverProps> => {
// //   const onExtraActionClick: CrafterQProps['onExtraActionClick'] = (e, id, content, api) =>
// //     getHostToGuestBus().next({ type: CrafterQResultMessageId, payload: { id, content } });
// //   return {
// //     ...other //,
// // //    CrafterQProps: { ...other?.CrafterQProps, userName: createUsername(user), onExtraActionClick }
// //   };
// };
function CrafterQHelper(props) {
    const { ui, enableCustomModel = 'true' } = props;
    const user = useActiveUser();
    const [open, setOpen] = useState(false);
    const [isMinimized, setIsMinimized] = useState(false);
    // const [CrafterQPopoverProps, setCrafterQPopoverProps] = useState<Partial<CrafterQPopoverProps>>(() =>
    //   createCrafterQPopoverProps(user)
    // );
    const handleOpenButtonClick = () => {
        setOpen(true);
        setIsMinimized(false);
    };
    const handleClose = () => {
        getHostToGuestBus().next({ type: CrafterQClosedMessageId });
        setOpen(false);
        //  setCrafterQPopoverProps(createCrafterQPopoverProps(user));
    };
    useEffect(() => {
        if (!helperListening) {
            helperListening = true;
            const subscription = merge(getGuestToHostBus(), getHostToHostBus()).subscribe((message) => {
                // if (message.type === openCrafterQMessageId) {
                //   setCrafterQPopoverProps(createCrafterQPopoverProps(user, message.payload));
                //   setOpen(true);
                //   setIsMinimized(false);
                // }
            });
            return () => {
                helperListening = false;
                subscription.unsubscribe();
            };
        }
    }, [user]);
    return (jsxs(Fragment, { children: [Boolean(ui) &&
                (ui === 'IconButton' ? (jsx(Tooltip, { title: "CrafterQ Authoring Assistant", children: jsx(IconButton, { onClick: handleOpenButtonClick, children: jsx(CrafterQLogo, {}) }) })) : (jsx(ToolsPanelListItemButton, { icon: { id: 'craftercms.components.aiassistant.CrafterQLogo' }, title: "CrafterQ Authoring Assistant", onClick: handleOpenButtonClick }))), jsx(CrafterQPopover
            //{...CrafterQPopoverProps}
            , { 
                //{...CrafterQPopoverProps}
                enableCustomModel: enableCustomModel.toLowerCase() === 'true', open: open, onClose: handleClose, isMinimized: isMinimized, onMinimize: () => setIsMinimized(true), onMaximize: () => setIsMinimized(false) })] }));
}

const plugin = {
    locales: undefined,
    scripts: undefined,
    stylesheets: undefined,
    id: 'craftercms.crafterq',
    widgets: {
        [helperWidgetId]: CrafterQHelper,
        [logoWidgetId]: CrafterQLogo,
        //    [chatWidgetId]: CrafterQ,
        [popoverWidgetId]: CrafterQPopover
    }
};

export { CrafterQPopover, plugin as default };
