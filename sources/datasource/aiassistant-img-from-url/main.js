/*
 * Copyright (C) 2026 Crafter Software Corporation. All Rights Reserved.
 *
 * Form engine data source: import an image from a remote https URL into the repository (same idea as
 * img-desktop-upload, but the server downloads the URL). Use for image-picker / RTE after adding this
 * datasource in the content type. CrafterQ chat drag-and-drop uses the same REST endpoint from React.
 */
/* global CStudioForms, YAHOO, CStudioAuthoring, CMgs, CStudioAuthoringContext, CrafterCMSNext */

function crafterqXsrfHeaders() {
  var m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
  var token = m ? decodeURIComponent(m[1]) : '';
  var headers = { 'Content-Type': 'application/json' };
  if (token) headers['X-XSRF-TOKEN'] = token;
  return headers;
}

function crafterqUnwrapPluginScriptBody(j) {
  if (j && typeof j === 'object' && j.result != null && typeof j.result === 'object' && !Array.isArray(j.result)) {
    return j.result;
  }
  return j;
}

function crafterqImportImageFromUrl(site, imageUrl, repoPath, fileName, objectId, objectGroupId) {
  var body = { imageUrl: imageUrl, repoPath: repoPath };
  if (fileName) body.fileName = fileName;
  if (objectId) body.objectId = objectId;
  if (objectGroupId) body.objectGroupId = objectGroupId;
  return fetch(
    '/studio/api/2/plugin/script/plugins/org/craftercms/aiassistant/studio/aiassistant/authoring/import-image-from-url?siteId=' +
      encodeURIComponent(site),
    { method: 'POST', credentials: 'same-origin', headers: crafterqXsrfHeaders(), body: JSON.stringify(body) }
  ).then(function (r) {
    return r.json().then(function (j) {
      var payload = crafterqUnwrapPluginScriptBody(j);
      if (!r.ok || payload.ok === false) throw new Error(payload.message || j.message || r.statusText || String(r.status));
      return payload;
    });
  });
}

CStudioForms.Datasources.CrafterqImgFromUrl =
  CStudioForms.Datasources.CrafterqImgFromUrl ||
  function (id, form, properties, constraints) {
    this.id = id;
    this.form = form;
    this.properties = properties;
    this.constraints = constraints;
    this.repoPath = '/static-assets/item/images/{yyyy}/{mm}/{dd}/';
    for (var i = 0; i < properties.length; i++) {
      if (properties[i].name === 'repoPath') {
        this.repoPath = properties[i].value || this.repoPath;
      }
    }
    return this;
  };

YAHOO.extend(CStudioForms.Datasources.CrafterqImgFromUrl, CStudioForms.CStudioFormDatasource, {
  getLabel: function () {
    return 'CrafterQ — Image from URL';
  },

  insertImageAction: function (insertCb, file) {
    var site = CStudioAuthoringContext.site;
    var path = this.repoPath || '/static-assets/item/images/{yyyy}/{mm}/{dd}/';
    path = this.processPathsForMacros(path);
    var _self = this;
    var CMgs = CStudioAuthoring.Messages;
    var langBundle = CMgs.getBundle('contentTypes', CStudioAuthoringContext.lang);

    var finishSuccess = function (relativeUrl, previewUrl, fileName) {
      insertCb.success({
        fileName: fileName,
        relativeUrl: relativeUrl,
        previewUrl: previewUrl
      });
    };

    if (!path || path === '') {
      var errorString = CMgs.format(langBundle, 'noPathSetError');
      errorString = errorString.replace('{DATASOURCENAME}', ' ' + this.getName() + ' ');
      CStudioAuthoring.Operations.showSimpleDialog(
        'error-dialog',
        CStudioAuthoring.Operations.simpleDialogTypeINFO,
        'Notification',
        errorString,
        null,
        YAHOO.widget.SimpleDialog.ICON_BLOCK,
        'studioDialog'
      );
      return;
    }

    if (file) {
      CrafterCMSNext.services.content.uploadDataUrl(site, file, path, '_csrf').subscribe(
        function (response) {
          if (response.type === 'complete') {
            var item = response.payload.body.message;
            var relativeUrl = item.uri;
            var previewUrl = CStudioAuthoringContext.previewAppBaseUri + relativeUrl + '?' + new Date().getTime();
            finishSuccess(relativeUrl, previewUrl, item.name);
          }
        },
        function (error) {
          insertCb.failure(error);
        }
      );
      return;
    }

    var url = window.prompt(
      'Paste image URL (https). The server will download it into:\n' + path + '\n\n(OpenAI / temporary URLs are OK.)'
    );
    if (!url || !url.trim()) {
      insertCb.failure('Cancelled');
      return;
    }
    url = url.trim();
    if (url.indexOf('data:image/') === 0) {
      CrafterCMSNext.services.content.uploadDataUrl(site, url, path, '_csrf').subscribe(
        function (response) {
          if (response.type === 'complete') {
            var item = response.payload.body.message;
            var relativeUrl = item.uri;
            var previewUrl = CStudioAuthoringContext.previewAppBaseUri + relativeUrl + '?' + new Date().getTime();
            finishSuccess(relativeUrl, previewUrl, item.name);
          }
        },
        function (error) {
          insertCb.failure(error);
        }
      );
      return;
    }

    crafterqImportImageFromUrl(site, url, path, null, null, null)
      .then(function (data) {
        var relativeUrl = data.relativeUrl;
        var previewUrl = CStudioAuthoringContext.previewAppBaseUri + relativeUrl + '?' + new Date().getTime();
        finishSuccess(relativeUrl, previewUrl, data.fileName);
      })
      .catch(function (e) {
        insertCb.failure(e && e.message ? e.message : String(e));
      });
  },

  createPreviewUrl: function (imagePath) {
    return CStudioAuthoringContext.previewAppBaseUri + imagePath;
  },

  cleanPreviewUrl: function (previewUrl) {
    var url = previewUrl;
    if (previewUrl.indexOf(CStudioAuthoringContext.previewAppBaseUri) !== -1) {
      url = previewUrl.substring(CStudioAuthoringContext.previewAppBaseUri.length);
      if (url.substring(0, 1) !== '/') url = '/' + url;
    }
    return url;
  },

  deleteImage: function () {},

  getInterface: function () {
    return 'image';
  },

  getName: function () {
    return 'aiassistant-img-from-url';
  },

  getSupportedProperties: function () {
    var CMgs = CStudioAuthoring.Messages;
    var langBundle = CMgs.getBundle('contentTypes', CStudioAuthoringContext.lang);
    return [
      {
        label: CMgs.format(langBundle, 'repositoryPath'),
        name: 'repoPath',
        type: 'content-path-input',
        defaultValue: '/static-assets/item/images/{yyyy}/{mm}/{dd}/',
        rootPath: '/static-assets',
        validations: {
          regex: /^\/static-assets(\/.*)?$/
        }
      }
    ];
  },

  getSupportedConstraints: function () {
    var CMgs = CStudioAuthoring.Messages;
    var langBundle = CMgs.getBundle('contentTypes', CStudioAuthoringContext.lang);
    return [{ label: CMgs.format(langBundle, 'required'), name: 'required', type: 'boolean' }];
  }
});

// Must match getName(); Studio resolves this key after loading the script (content type builder, form engine).
CStudioAuthoring.Module.moduleLoaded('aiassistant-img-from-url', CStudioForms.Datasources.CrafterqImgFromUrl);
