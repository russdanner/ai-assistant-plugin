import type { UnknownAction } from 'redux';
import { initToolbarConfig } from '@craftercms/studio-ui/state/actions/preview';
import { aiAssistantStudioPluginId, helperWidgetId } from './consts';

const PREVIEW_TOOLBAR_ID = 'craftercms.components.PreviewToolbar';
const ADDRESS_BAR_ID = 'craftercms.components.PreviewAddressBar';

/** Avoid duplicate `initToolbarConfig` for the same broken `ui.xml` snapshot (multiple Helper mounts). */
const patchedSourceXml = new Set<string>();

function ensureIconButtonConfiguration(doc: Document, helper: Element): void {
	let conf = helper.querySelector(':scope > configuration');
	if (!conf) {
		conf = doc.createElement('configuration');
		conf.setAttribute('ui', 'IconButton');
		helper.appendChild(conf);
	} else if (!conf.getAttribute('ui')) {
		conf.setAttribute('ui', 'IconButton');
	}
}

/**
 * Studio marketplace merge can (a) put our `<plugin>` inside PreviewAddressBar, (b) leave the Helper under
 * `rightSection/widgets` (descriptor default for safe wiring). Normalize to the supported shape: Helper is a
 * sibling of PreviewAddressBar under `middleSection/widgets`.
 */
export function normalizePreviewToolbarMiddleSectionUiXml(xml: string): string | null {
	if (!xml || xml.indexOf(PREVIEW_TOOLBAR_ID) === -1) return null;

	const doc = new DOMParser().parseFromString(xml, 'application/xml');
	if (doc.querySelector('parsererror')) return null;

	const toolbar = doc.querySelector(`widget[id="${PREVIEW_TOOLBAR_ID}"]`);
	const configuration = toolbar?.querySelector(':scope > configuration');
	const middleSection = configuration?.querySelector(':scope > middleSection');
	const widgets = middleSection?.querySelector(':scope > widgets');
	if (!widgets || !middleSection) return null;

	let changed = false;

	// Legacy descriptor merge stubs (no longer emitted); strip if present.
	doc.querySelectorAll(`plugin[id="${aiAssistantStudioPluginId}"] > installationAnchor`).forEach((n) => {
		n.remove();
		changed = true;
	});

	// Marketplace-safe install merges the Helper under rightSection/widgets; move it beside the address bar.
	const rightWidgets = configuration?.querySelector(':scope > rightSection > widgets');
	const helperInRight = rightWidgets?.querySelector(`:scope > widget[id="${helperWidgetId}"]`) ?? null;
	if (helperInRight) {
		const dupMiddle = widgets.querySelector(`:scope > widget[id="${helperWidgetId}"]`);
		if (dupMiddle && dupMiddle !== helperInRight) {
			helperInRight.remove();
			changed = true;
		} else if (!dupMiddle) {
			widgets.appendChild(helperInRight);
			ensureIconButtonConfiguration(doc, helperInRight);
			changed = true;
		}
	}

	// Case A: our plugin nested under PreviewAddressBar — lift into Helper widget sibling.
	const addressBar = widgets.querySelector(`widget[id="${ADDRESS_BAR_ID}"]`);
	const strayPlugin = addressBar?.querySelector(`:scope > plugin[id="${aiAssistantStudioPluginId}"]`) ?? null;
	if (addressBar && strayPlugin) {
		let helper = widgets.querySelector(`:scope > widget[id="${helperWidgetId}"]`);
		if (!helper) {
			helper = doc.createElement('widget');
			helper.setAttribute('id', helperWidgetId);
			widgets.appendChild(helper);
		}
		const already = helper.querySelector(`:scope > plugin[id="${aiAssistantStudioPluginId}"]`);
		if (already && already !== strayPlugin) {
			strayPlugin.remove();
		} else if (!already) {
			helper.appendChild(strayPlugin);
		}
		ensureIconButtonConfiguration(doc, helper);
		changed = true;
	}

	// Case B: Helper is a direct child of middleSection (sibling of widgets) — move into widgets.
	const misplacedHelpers = Array.from(middleSection.querySelectorAll(':scope > widget')).filter(
		(el) => el.getAttribute('id') === helperWidgetId
	);
	for (const h of misplacedHelpers) {
		widgets.appendChild(h);
		ensureIconButtonConfiguration(doc, h);
		changed = true;
	}

	if (!changed) return null;
	return new XMLSerializer().serializeToString(doc);
}

export function dispatchPreviewToolbarUiXmlFixIfNeeded(
	xml: string | null | undefined,
	dispatch: (action: UnknownAction) => void
): void {
	if (!xml) return;
	if (patchedSourceXml.has(xml)) return;
	const normalized = normalizePreviewToolbarMiddleSectionUiXml(xml);
	if (normalized == null || normalized === xml) return;
	patchedSourceXml.add(xml);
	dispatch(initToolbarConfig({ configXml: normalized }));
}
