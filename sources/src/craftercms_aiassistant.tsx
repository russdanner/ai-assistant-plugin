'use strict';
import { take, takeUntil } from 'rxjs';
import {
  Editor,
} from 'tinymce';
import { aiAssistantClosedMessageId, openAiAssistantMessageId, popoverWidgetId } from './consts';
import type { AiAssistantPopoverProps } from './AiAssistantPopover';

export type AiAssistantMessage = {
  role: string;
  content: string;
};

export interface CrafterCMSAiAssistantConfig {
  strings?: {
    /** Toolbar tooltip for the main “open assistant” button (`aiAssistantOpen`). */
    openAiAssistant?: string;
    /** @deprecated Use `openAiAssistant`. */
    crafterqDialog?: string;
    /** Toolbar tooltip for the shortcuts menu (`aiAssistantShortcuts`). */
    aiAssistantShortcuts?: string;
    /** @deprecated Use `aiAssistantShortcuts`. */
    crafterqShortcuts?: string;
  };
  prependMessages?: AiAssistantMessage[];
  shortcuts?: Array<{
    label: string;
    messages?: AiAssistantMessage[];
    shortcuts?: { label: string; messages: AiAssistantMessage[] }[];
  }>;
  /** Opens the AI Assistant with the built message list (selection/context). */
  onOpenAiAssistant?: (editor: Editor, api: unknown, messages: AiAssistantMessage[]) => void;
  /** @deprecated Use `onOpenAiAssistant`. */
  oncrafterqDialog?: (editor: Editor, api: unknown, messages: AiAssistantMessage[]) => void;
  onShortcutClick?: (editor: Editor, api: unknown, messages: AiAssistantMessage[]) => void;
  emptyStateOptions?: unknown;
  AiAssistantPopoverProps?: Partial<AiAssistantPopoverProps>;
}

const BASE_CONFIG: Partial<CrafterCMSAiAssistantConfig> = {
  strings: {
    openAiAssistant: 'Open AI Assistant',
    aiAssistantShortcuts: 'AI Shortcuts'
  },
  prependMessages: [],
  shortcuts: []
};

// const BASE_CONFIG: Partial<CrafterCMSAiAssistantConfig> = {
//   strings: {
//     crafterqDialog: 'Open AI Assistant',
//     crafterqShortcuts: 'AI Shortcuts'
//   },
//   prependMessages: [
//     // Answer the question based on the context below. The response should be in HTML format. The response should preserve any HTML formatting, links, and styles in the context.
//     // {
//     //   role: 'system',
//     //   content:
//     //     'Answer the question based on the context below in plain text format. Do not add quotes to your replies'
//     // }
//   ],
//   shortcuts: [
//     {
//       label: 'Elaborate',
//       messages: [
//         {
//           role: 'user',
//           content:
//             'Elaborate the text with descriptive language and more detailed explanations to make the writing easier to understand and increase the length of the content. Context: """{context}"""'
//         }
//       ]
//     },
//     {
//       label: 'Enhance',
//       messages: [
//         {
//           role: 'user',
//           content:
//             'Without losing its original meaning, enhance this text. Remove spelling and grammar errors, use descriptive language and best writing practices. Context: """{context}"""'
//         }
//       ]
//     },
//     {
//       label: 'Simplify',
//       messages: [
//         {
//           role: 'user',
//           content:
//             'Simplify the language and reduce the complexity in the following text so that the content is easy to understand. Context: """{context}"""'
//         }
//       ]
//     },
//     {
//       label: 'Summarize',
//       messages: [
//         {
//           role: 'user',
//           content: 'Concisely summarize the key concepts in the following text. Context: """{context}"""'
//         }
//       ]
//     },
//     {
//       label: 'Trim',
//       messages: [
//         {
//           role: 'user',
//           content:
//             'Remove redundant, repetitive, or non-essential writing in this text without changing the meaning or losing any key information. Context: """{context}"""'
//         }
//       ]
//     },
//     {
//       label: 'Update Style',
//       shortcuts: [
//         {
//           label: 'Business',
//           messages: [
//             {
//               role: 'user',
//               content: 'Rewrite this text using formal and business professional language. Context: """{context}"""'
//             }
//           ]
//         },
//         {
//           label: 'Legal',
//           messages: [
//             {
//               role: 'user',
//               content:
//                 'Rewrite this text using legal terminology and legal professional language. Context: """{context}"""'
//             }
//           ]
//         },
//         {
//           label: 'Poetic',
//           messages: [
//             {
//               role: 'user',
//               content:
//                 'Rewrite this text as a poem using poetic techniques without losing the original meaning. Context: """{context}"""'
//             }
//           ]
//         },
//         {
//           label: 'Journalism',
//           messages: [
//             {
//               role: 'user',
//               content:
//                 'Rewrite this text as a journalist using engaging language to convey the importance of the information. Context: """{context}"""'
//             }
//           ]
//         },
//         {
//           label: 'Medical',
//           messages: [
//             {
//               role: 'user',
//               content:
//                 'Rewrite this text as a medical professional using valid medical terminology. Context: """{context}"""'
//             }
//           ]
//         }
//       ]
//     },
//     {
//       label: 'Update Tone',
//       shortcuts: [
//         {
//           label: 'Professional',
//           messages: [
//             {
//               role: 'user',
//               content:
//                 'Rewrite this text using respectful, professional, polished, and formal language to convey deep expertise and competence. Context: """{context}"""'
//             }
//           ]
//         },
//         {
//           label: 'Confident',
//           messages: [
//             {
//               role: 'user',
//               content:
//                 'Rewrite this text using confident, optimistic, and compelling language to convey confidence. Context: """{context}"""'
//             }
//           ]
//         },
//         {
//           label: 'Direct',
//           messages: [
//             {
//               role: 'user',
//               content:
//                 'Rewrite this text to have direct language using only the essential information. Context: """{context}"""'
//             }
//           ]
//         },
//         {
//           label: 'Casual',
//           messages: [
//             {
//               role: 'user',
//               content:
//                 'Rewrite this text with informal and casual language to convey a casual conversation. Context: """{context}"""'
//             }
//           ]
//         },
//         {
//           label: 'Friendly',
//           messages: [
//             {
//               role: 'user',
//               content:
//                 'Rewrite this content using warm, comforting, and friendly language to convey understanding and empathy. Context: """{context}"""'
//             }
//           ]
//         }
//       ]
//     }
//   ],
//   oncrafterqDialog: (editor) => alert(editor, 'No action configured to handle opening the AI assistant.'),
//   onShortcutClick: (editor) => alert(editor, 'No action configured to handle shortcut click.')
// };

const craftercms: any = (window as any).craftercms;
const tinymce: any = (window as any).tinymce;
const xb: any = craftercms?.xb;
const isXb = Boolean(xb);

const pluginManager = tinymce.util.Tools.resolve('tinymce.PluginManager');

const alert = (editor, message) => {
  editor.windowManager.alert(message);
};

const setContent = (editor, html) => {
  // editor.focus();
  // editor.undoManager.transact(() => {
  //   editor.setContent(html);
  // });
  // editor.selection.setCursorLocation();
  // editor.nodeChanged();
  // editor.setContent(html);
  editor.insertContent(html);
};

const getSource = (editor) => {
  return editor.getContent({ source_view: true });
};

const getContent = (editor) => {
  return editor.getContent({ format: 'text' });
};

const getSelection = (editor) => {
  return editor.selection.getContent({ format: 'text' });
};

const handleChatActionClick = (editor: Editor, id: string, content: string) => {
  switch (id) {
    case 'insert':
      // Don't see a way of avoiding the editor regaining the focus using "insertContent".
      // editor.insertContent(content, { no_events: true, focus: false });
      // Hence, using "setContent" instead.
      editor.selection.setContent(content);
      break;
  }
};

const tellStudioToOpenAiAssistant = (editor, props) => {
  xb.post(openAiAssistantMessageId, props);
  xb.fromTopic(aiAssistantClosedMessageId)
    .pipe(take(1))
    .subscribe(() => {
      setTimeout(() => editor.focus());
    });
  // xb.fromTopic(CrafterQResultMessageId)
  //   .pipe(takeUntil(xb.fromTopic(aiAssistantClosedMessageId)))
  //   .subscribe(({ payload: { id, content } }) => {
  //     handleChatActionClick(editor, id, content);
  //   });
};

const createDefaultHandler = (config) => {
  return (editor, api, messages) => {
    if (!isXb) {
      const site = craftercms.getStore().getState().sites.active;
      craftercms.services.plugin
        .importPlugin(site, 'aiassistant', 'components', 'index.js', 'org.craftercms.aiassistant.studio')
        .then((plugin) => {
          const store = craftercms?.getStore?.();
          const st = store?.getState?.();
          const rawUser = st?.user?.username;
          const userName =
            (typeof rawUser === 'string' && rawUser.trim()) || (rawUser != null && String(rawUser).trim()) || 'anonymous';
          const container = document.createElement('div');
          const root = craftercms.libs.ReactDOMClient.createRoot(container);
          const AiAssistantPopover: any = plugin.widgets[popoverWidgetId]; // Same as craftercms.utils.constants.components.get('...');
          const CrafterRoot: any = craftercms.utils.constants.components.get('craftercms.components.CrafterCMSNextBridge');
          root.render(
            <CrafterRoot>
              <AiAssistantPopover
                open
                onClose={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  root.unmount();
                  container.remove();
                }}
                {...config.AiAssistantPopoverProps}
                AiAssistantProps={{
                  userName,
                  emptyStateOptions: config.emptyStateOptions,
                  initialMessages: messages,
                  extraActions: [{ label: 'Insert', id: 'insert' }],
                  onExtraActionClick: ((e, id, content, api) => {
                    handleChatActionClick(editor, id, content);
                  }) as any
                }}
              />
            </CrafterRoot>
          );
        });
    } else {
      tellStudioToOpenAiAssistant(editor, {
        ...config.AiAssistantPopoverProps,
        AiAssistantProps: {
          ...config.AiAssistantPopoverProps?.AiAssistantProps,
          emptyStateOptions: config.emptyStateOptions,
          initialMessages: messages,
          extraActions: [{ label: 'Insert', id: 'insert' }]
        }
      });
    }
  };
};

pluginManager.add('craftercms_aiassistant', function (editor: Editor) {
  const configArg = editor.getParam('craftercms_aiassistant') as CrafterCMSAiAssistantConfig | undefined;
  const mergedStrings = {
    ...BASE_CONFIG.strings,
    ...configArg?.strings
  };
  const instanceConfig: CrafterCMSAiAssistantConfig & { strings: NonNullable<CrafterCMSAiAssistantConfig['strings']> } = {
    ...BASE_CONFIG,
    ...configArg,
    strings: {
      ...mergedStrings,
      openAiAssistant:
        mergedStrings.openAiAssistant ??
        mergedStrings.crafterqDialog ??
        'Open AI Assistant',
      aiAssistantShortcuts:
        mergedStrings.aiAssistantShortcuts ??
        mergedStrings.crafterqShortcuts ??
        'AI Shortcuts'
    }
  };

  const userOpen =
    configArg?.onOpenAiAssistant ?? configArg?.oncrafterqDialog;
  if (!userOpen || !configArg?.onShortcutClick) {
    const defaultHandler = createDefaultHandler(instanceConfig);
    instanceConfig.onOpenAiAssistant = defaultHandler;
    instanceConfig.onShortcutClick = defaultHandler;
  } else {
    instanceConfig.onOpenAiAssistant = userOpen;
    instanceConfig.onShortcutClick = configArg.onShortcutClick;
  }

  editor.ui.registry.addButton('aiAssistantOpen', {
    icon: 'ai',
    tooltip: instanceConfig.strings.openAiAssistant,
    onAction(api) {
      const content = getSelection(editor).trim() || getContent(editor);
      const messages: AiAssistantMessage[] = [...instanceConfig.prependMessages].map((item) => ({
        ...item,
        content: item.content.replace('{context}', content)
      }));
      const selection = getSelection(editor);
      if (selection) {
        messages.push({ role: 'system', content: `Context: ${selection}` });
      }
      instanceConfig.onOpenAiAssistant!(editor, api, messages);
    }
  });
  editor.ui.registry.addMenuButton('crafterqshortcuts', {
    icon: 'ai-prompt',
    tooltip: instanceConfig.strings.aiAssistantShortcuts,
    fetch(callback) {
      const onAction = (api: any, item: any) => {
        const content = getSelection(editor).trim() || getContent(editor);
        const messages: AiAssistantMessage[] = [...instanceConfig.prependMessages, ...(item.messages ?? [])].map((item) => ({
          ...item,
          content: item.content.replace('{context}', content)
        }));
        instanceConfig.onShortcutClick(editor, api, messages);
      };
      const mapper = (shortcut: any) => {
        const isNested = 'shortcuts' in shortcut;
        return {
          type: isNested ? 'nestedmenuitem' : 'menuitem',
          text: shortcut.label,
          icon: shortcut.icon,
          ...(isNested
            ? { getSubmenuItems: () => shortcut.shortcuts.map(mapper) }
            : { onAction: (api) => onAction(api, shortcut) })
        };
      };
      callback(instanceConfig.shortcuts.map(mapper));
    }
  });
  editor.ui.registry.addSplitButton('aiassistant', {
    icon: 'aiassistant',
    tooltip: 'Open AI',
    fetch(callback) {
      const mapper = (shortcut, index, collection, parent) => {
        const hasChildren = 'shortcuts' in shortcut;
        return hasChildren
          ? shortcut.shortcuts.map((a, b, c) => mapper(a, b, c, shortcut))
          : {
              type: 'choiceitem',
              text: parent ? `${parent.label}: ${shortcut.label}` : shortcut.label,
              icon: shortcut.icon,
              value: shortcut // instanceConfig.shortcuts[index] === shortcut ? `index` : ``
            };
      };
      callback(instanceConfig.shortcuts.flatMap(mapper));
    },
    onAction(api) {
      const content = getSelection(editor).trim() || getContent(editor);
      const messages: AiAssistantMessage[] = [...instanceConfig.prependMessages].map((item) => ({
        ...item,
        content: item.content.replace('{context}', content)
      }));
      instanceConfig.onOpenAiAssistant!(editor, api, messages);
    },
    onItemAction(api, item: any) {
      const content = getSelection(editor).trim() || getContent(editor);
      const messages: AiAssistantMessage[] = [...instanceConfig.prependMessages, ...(item.messages ?? [])].map((item) => ({
        ...item,
        content: item.content.replace('{context}', content)
      }));
      instanceConfig.onShortcutClick(editor, api, messages);
    }
  });

  return {};
});
