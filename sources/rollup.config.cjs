// const babel = require('@rollup/plugin-babel');
// const typescript = require('rollup-plugin-typescript2');
const typescript = require('@rollup/plugin-typescript');
const commonjs = require('@rollup/plugin-commonjs');
const resolve = require('@rollup/plugin-node-resolve');
const replaceImportsWithVars = require('rollup-plugin-replace-imports-with-vars');
const json = require('@rollup/plugin-json');
// const pkg = require('./package.json');
const copy = require('rollup-plugin-copy');
// const { terser } = require('rollup-plugin-terser');
const replace = require('@rollup/plugin-replace');
const { parseSync } = require('env-file-parser');

const envFile = parseSync('.env');
const extensions = ['.js', '.jsx', '.ts', '.tsx'];

// const craftercmsLibs = 'craftercms.libs?';
const globals = {
  react: 'craftercms.libs.React',
  rxjs: 'craftercms.libs.rxjs',
  'rxjs/operators': 'craftercms.libs.rxjs',
  // jsx runtime part of Studio's runtime starting 4.1.2
  'react/jsx-runtime': 'craftercms.libs?.reactJsxRuntime',
  '@emotion/css/create-instance': 'craftercms.libs.createEmotion',
  'react-dom': 'craftercms.libs.ReactDOM',
  'react-intl': 'craftercms.libs.ReactIntl',
  'react-redux': 'craftercms.libs.ReactRedux',
  '@mui/material': 'craftercms.libs.MaterialUI',
  '@mui/material/styles': 'craftercms.libs.MaterialUI',
  '@craftercms/studio-ui': 'craftercms.components',
  '@craftercms/studio-ui/components': 'craftercms.components',
  '@mui/material/utils': 'craftercms.libs.MaterialUI',
  '@reduxjs/toolkit': 'craftercms.libs.ReduxToolkit'
};

const replacementRegExps = {
  '@craftercms/studio-ui/(components|icons|utils|services)/(.+)': (exec) =>
    `craftercms.${exec[1]}.${exec[2].split('/').pop()}`,
  // Do not match `utils` here: `@mui/material/utils` is mapped in `globals` to the root
  // `craftercms.libs.MaterialUI`. A subpath `MaterialUI.utils` breaks the commonjs plugin
  // (invalid `...utils?commonjs-external` in the bundle) and prevents the whole plugin from loading.
  '@mui/material/(?!utils$)(.+)': (exec) => `craftercms.libs.MaterialUI.${exec[1]}`,
  '@mui/icons-material/(.+(Rounded|Outlined))$': (exec) => `craftercms.utils.constants.components.get('${exec[0]}')`
};

// Form assistant accordion UI: `src/AiAssistantFormControlPanel.tsx` (do not revert to shared-chat layout).
// Form-engine snapshot type: `src/aiAssistantFormAuthoringTypes.ts` (XB vs form chat refactors touch Chat/API, not panel markup).
// NOTE: Crafter Studio installs authoring static assets into:
// {siteRepo}/config/studio/static-assets/plugins/<pluginId path>/...
//
// Preview UI bundles use type=aiassistant → .../studio/aiassistant/<name>/ (e.g. components, tinymce).
// Form-engine datasources use type=datasource → .../studio/datasource/<name>/ (no second product segment).
// getPluginFile: ...&type=datasource&name=aiassistant-img-from-url&filename=main.js
//
/** @rollup/plugin-commonjs can emit invalid `...MaterialUI.utils?commonjs-external` when icons pull in `@mui/material/utils`; `replace-imports-with-vars` only rewrites `import` lines, not these requires. Studio exposes `createSvgIcon` on `craftercms.libs.MaterialUI` root. */
function fixMaterialUiUtilsCommonjsArtifact() {
  const broken = /craftercms\.libs\.MaterialUI\.utils\?commonjs-external/g;
  return {
    name: 'fix-material-ui-utils-commonjs-artifact',
    renderChunk(code) {
      if (!code.includes('MaterialUI.utils?commonjs-external')) {
        return null;
      }
      return { code: code.replace(broken, 'craftercms.libs.MaterialUI'), map: null };
    }
  };
}

const studioPluginRoot = '../authoring/static-assets/plugins/org/craftercms/aiassistant/studio';
const basePluginDir = `${studioPluginRoot}/aiassistant`;
// Some sites / older ui.xml still resolve `org.craftercms` → this path; keep it in sync or ICE loses `DialogContent`.
const legacyComponentsDir = '../authoring/static-assets/org/craftercms/aiassistant/components';
const legacyDatasourceDir =
  '../authoring/static-assets/org/craftercms/aiassistant/datasource/aiassistant-img-from-url';
const legacyTinymceDir = '../authoring/static-assets/org/craftercms/aiassistant/tinymce';
const publicDir = './public';

module.exports = [
  {
    context: 'this',
    input: './src/craftercms_aiassistant.tsx',
    output: [
      {
        file: `${publicDir}/craftercms_aiassistant.js`,
        format: 'iife',
        globals
      }
    ],
    external: Object.keys(globals).filter(key => !key.includes('rxjs')).concat(Object.keys(replacementRegExps).map((str) => new RegExp(str))),
    plugins: [
      json(),
      replace({
        preventAssignment: true,
        'process.env.NODE_ENV': JSON.stringify('production'),
        'import.meta.env.MODE': JSON.stringify('production'),
        'import.meta.env.NODE_ENV': JSON.stringify('production'),
        'import.meta.env.VITE_OPENAI_API_KEY': `"${envFile.VITE_OPENAI_API_KEY}"`
      }),
      // babel({ babelHelpers: 'bundled', extensions }),
      typescript({ tsconfig: './tsconfig.json', compilerOptions: { noEmit: false } }),
      // typescript({
      //   tsconfigOverride: { compilerOptions: { declaration: false, noEmit: false, emitDeclarationOnly: false } }
      // }),
      replaceImportsWithVars({
        replacementLookup: globals,
        replacementRegExps
      }),
      // !!: If used, terser should be after `replaceImportsWithVars`
      // terser(),
      resolve({ extensions }),
      commonjs(),
      copy({
        hook: 'closeBundle',
        targets: [
          {
            src: './public/*.js',
            dest: `${basePluginDir}/tinymce`
          },
          {
            src: './public/*.js',
            dest: legacyTinymceDir
          }
        ]
      })
    ]
  },
  !process.env.tinymce && {
    context: 'this',
    input: 'index.tsx',
    output: [
      {
        file: `${basePluginDir}/components/index.js`,
        format: 'es',
        globals
      }
    ],
    external: Object.keys(globals).concat(Object.keys(replacementRegExps).map((str) => new RegExp(str))),
    plugins: [
      json(),
      replace({
        preventAssignment: true,
        'process.env.NODE_ENV': JSON.stringify('production'),
        'import.meta.env.MODE': JSON.stringify('production'),
        'import.meta.env.NODE_ENV': JSON.stringify('production'),
        'import.meta.env.VITE_OPENAI_API_KEY': `"${envFile.VITE_OPENAI_API_KEY}"`
      }),
      // babel({ babelHelpers: 'bundled', extensions }),
      typescript({ tsconfig: './tsconfig.json', compilerOptions: { noEmit: false } }),
      // typescript({
      //   tsconfigOverride: { compilerOptions: { declaration: false, noEmit: false, emitDeclarationOnly: false } }
      // }),
      replaceImportsWithVars({
        replacementLookup: globals,
        replacementRegExps
      }),
      // !!: If used, terser should be after `replaceImportsWithVars`
      // terser(),
      resolve({ extensions }),
      commonjs(),
      fixMaterialUiUtilsCommonjsArtifact(),
      copy({
        hook: 'closeBundle',
        targets: [
          {
            src: './public/*.js',
            dest: `${basePluginDir}/tinymce`
          },
          {
            src: `${basePluginDir}/components/index.js`,
            dest: legacyComponentsDir
          },
          {
            src: 'datasource/aiassistant-img-from-url/main.js',
            dest: `${basePluginDir}/datasource/aiassistant-img-from-url`
          },
          {
            src: 'datasource/aiassistant-img-from-url/main.js',
            dest: `${studioPluginRoot}/datasource/aiassistant-img-from-url`
          },
          {
            src: 'datasource/aiassistant-img-from-url/main.js',
            dest: legacyDatasourceDir
          },
          // Canonical source: ./control/ai-assistant/main.js (hand-maintained). Do not edit authoring/.../main.js only.
          {
            src: 'control/ai-assistant/main.js',
            dest: `${studioPluginRoot}/control/ai-assistant`
          }
        ]
      })
    ]
  }
].filter(Boolean);
