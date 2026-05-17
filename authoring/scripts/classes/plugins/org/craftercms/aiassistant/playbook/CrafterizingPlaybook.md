# Crafterizing playbook (template → CrafterCMS site)

Editable reference for converting static HTML templates into a CrafterCMS project. Call **`GetCrafterizingPlaybook`** when starting or scoping a crafterization task.

## Goals

- **Content-first**: Author-editable text, images, links, and structure live in **content XML** and **FreeMarker** reads them; avoid hardcoded copy in FTL except wiring.
- **Components over duplication**: Repeated regions → reusable components and content types.
- **Structure parity**: Preserve HTML/CSS DOM shape (classes, wrappers, nesting) for pixel-accurate rendering.
- **Experience Builder**: Use `@crafter` / XB markup on fields authors should edit in-context.

## Repository layout (high level)

| Area | Purpose |
|------|---------|
| `/config/studio/content-types/` | **All** content type definitions (`config.xml`, `form-definition.xml`) — not under `/site/content-types/`. |
| `/templates/web/pages/` | Page FTL |
| `/templates/web/components/` | Component FTL |
| `/templates/web/fragments/` | Shared head, scripts, etc. |
| `/site/website/` | **One folder per page** with `index.xml`; each page lists section components in **`sections_o`** (or your page model’s list field). |
| `/site/components/` | Shared section/item component XML |
| `/static-assets/` | CSS, JS, images (prefer `/static-assets/app/`, `/static-assets/images/`). **Do not** use `/static/...` in URLs — that is not the Crafter static-assets mount. |

## Phases (recommended order)

1. **Inventory**: List HTML pages; mark repeated blocks; list nested items (cards, FAQs, team members, etc.).
2. **Skeleton**: Directories, asset migration, optional sitemap/search if required by the project.
3. **Content model**: Page types + **every** component type (including **nested** item types). Add `sections_o` (or equivalent) **contentTypes** on pages to allow new section components.
4. **Templates**: FTL from HTML; `renderComponentCollection` / `@renderComponent` for sections and collections; preserve markup.
5. **XB**: Wrap editable outputs with appropriate `@crafter.*` macros; correct `$model` in loops.
6. **Content items**: XML for **every** page and component; populate **`sections_o`** and all collection references; CDATA for `*_html` fields.
7. **QA**: curl preview, compare DOM, fix FreeMarker boolean/path issues; verify OpenSearch/CDATA if indexing.

## Critical rules (checklist)

- **Content types** only under `/config/studio/content-types/`; create `config.xml` before `form-definition.xml`.
- **Pages**: Root `<page>`; include `file-name`, `internal-name`, `objectId`, `content-type`, `display-template`, dates; **`sections_o` populated** in order matching the page.
- **Components**: Root `<component>`; same required metadata pattern; collections filled with **item references** in order.
- **Field IDs** in XML must match the form definition; use suffixes `_t`, `_s`, `_html`, `_o`, `_b`, `_i` consistently.
- **Never infer** `contentTypeId` from filename (e.g. `index.xml` ≠ `/page/index`). Read `<content-type>` from the item XML or use **GetContentTypeFormDefinition** with **`contentPath`**.
- **RTE** fields: type `rte` in forms; wrap HTML in **CDATA** in content items for indexing.
- **Display templates**: kebab-case paths matching real FTL file names.
- **Tools in this plugin**: Use **GetContent**, **WriteContent**, **update_content**, **update_template**, **GetContentTypeFormDefinition**, **ListPagesAndComponents** as appropriate. Preparatory tools do not save until **WriteContent**.

## Nested components

Any repeating block **inside** a section (testimonial rows, product cards, nav columns) needs its **own** content type and items, referenced from the parent’s node-selector or repeat structure.

## Documentation

Maintain `/docs/` (README, pages, components, items, global, architecture diagram) in sync with the real model when the project uses project-level docs.

---

*This file is loaded at runtime by the Studio AI assistant plugin. Edit it to match your team’s conventions.*
