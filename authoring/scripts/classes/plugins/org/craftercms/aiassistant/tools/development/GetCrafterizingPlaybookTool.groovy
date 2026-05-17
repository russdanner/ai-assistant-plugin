package plugins.org.craftercms.aiassistant.tools.development

import plugins.org.craftercms.aiassistant.playbook.CrafterizingPlaybookLoader
import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas

class GetCrafterizingPlaybookTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'GetCrafterizingPlaybook' }

  @Override
  String description() { ToolPrompts.getDESC_GET_CRAFTERIZING_PLAYBOOK() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.CRAFTERIZING_PLAYBOOK }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def raw = CrafterizingPlaybookLoader.loadMarkdown()
    boolean fromEditableFile = raw != null && raw.toString().trim().length() > 0
    def md = fromEditableFile ? raw.toString() : CrafterizingPlaybookLoader.embeddedFallbackMarkdown()
    final int maxChars = 250_000
    if (md.length() > maxChars) {
      md = md.substring(0, maxChars) + "\n\n…[truncated at ${maxChars} characters]\n"
    }
    return [
      tool                  : 'GetCrafterizingPlaybook',
      markdown              : md,
      charCount             : md.length(),
      loadedFromEditableFile: fromEditableFile,
      playbookFileName      : CrafterizingPlaybookLoader.PLAYBOOK_FILE_NAME,
      hint                  : 'Use when planning or executing full HTML-template-to-CrafterCMS (crafterization) work; follow phases and critical rules, then use CMS tools to read/write content.'
    ]
  }
}
