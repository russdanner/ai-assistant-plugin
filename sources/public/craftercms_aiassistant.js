(function (jsxRuntime) {
    'use strict';

    /******************************************************************************
    Copyright (c) Microsoft Corporation.

    Permission to use, copy, modify, and/or distribute this software for any
    purpose with or without fee is hereby granted.

    THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH
    REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY
    AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
    INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM
    LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR
    OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
    PERFORMANCE OF THIS SOFTWARE.
    ***************************************************************************** */
    /* global Reflect, Promise, SuppressedError, Symbol, Iterator */

    var extendStatics = function(d, b) {
        extendStatics = Object.setPrototypeOf ||
            ({ __proto__: [] } instanceof Array && function (d, b) { d.__proto__ = b; }) ||
            function (d, b) { for (var p in b) if (Object.prototype.hasOwnProperty.call(b, p)) d[p] = b[p]; };
        return extendStatics(d, b);
    };

    function __extends(d, b) {
        if (typeof b !== "function" && b !== null)
            throw new TypeError("Class extends value " + String(b) + " is not a constructor or null");
        extendStatics(d, b);
        function __() { this.constructor = d; }
        d.prototype = b === null ? Object.create(b) : (__.prototype = b.prototype, new __());
    }

    function __values(o) {
        var s = typeof Symbol === "function" && Symbol.iterator, m = s && o[s], i = 0;
        if (m) return m.call(o);
        if (o && typeof o.length === "number") return {
            next: function () {
                if (o && i >= o.length) o = void 0;
                return { value: o && o[i++], done: !o };
            }
        };
        throw new TypeError(s ? "Object is not iterable." : "Symbol.iterator is not defined.");
    }

    function __read(o, n) {
        var m = typeof Symbol === "function" && o[Symbol.iterator];
        if (!m) return o;
        var i = m.call(o), r, ar = [], e;
        try {
            while ((n === void 0 || n-- > 0) && !(r = i.next()).done) ar.push(r.value);
        }
        catch (error) { e = { error: error }; }
        finally {
            try {
                if (r && !r.done && (m = i["return"])) m.call(i);
            }
            finally { if (e) throw e.error; }
        }
        return ar;
    }

    function __spreadArray(to, from, pack) {
        if (pack || arguments.length === 2) for (var i = 0, l = from.length, ar; i < l; i++) {
            if (ar || !(i in from)) {
                if (!ar) ar = Array.prototype.slice.call(from, 0, i);
                ar[i] = from[i];
            }
        }
        return to.concat(ar || Array.prototype.slice.call(from));
    }

    typeof SuppressedError === "function" ? SuppressedError : function (error, suppressed, message) {
        var e = new Error(message);
        return e.name = "SuppressedError", e.error = error, e.suppressed = suppressed, e;
    };

    function isFunction(value) {
        return typeof value === 'function';
    }

    function createErrorClass(createImpl) {
        var _super = function (instance) {
            Error.call(instance);
            instance.stack = new Error().stack;
        };
        var ctorFunc = createImpl(_super);
        ctorFunc.prototype = Object.create(Error.prototype);
        ctorFunc.prototype.constructor = ctorFunc;
        return ctorFunc;
    }

    var UnsubscriptionError = createErrorClass(function (_super) {
        return function UnsubscriptionErrorImpl(errors) {
            _super(this);
            this.message = errors
                ? errors.length + " errors occurred during unsubscription:\n" + errors.map(function (err, i) { return i + 1 + ") " + err.toString(); }).join('\n  ')
                : '';
            this.name = 'UnsubscriptionError';
            this.errors = errors;
        };
    });

    function arrRemove(arr, item) {
        if (arr) {
            var index = arr.indexOf(item);
            0 <= index && arr.splice(index, 1);
        }
    }

    var Subscription = (function () {
        function Subscription(initialTeardown) {
            this.initialTeardown = initialTeardown;
            this.closed = false;
            this._parentage = null;
            this._finalizers = null;
        }
        Subscription.prototype.unsubscribe = function () {
            var e_1, _a, e_2, _b;
            var errors;
            if (!this.closed) {
                this.closed = true;
                var _parentage = this._parentage;
                if (_parentage) {
                    this._parentage = null;
                    if (Array.isArray(_parentage)) {
                        try {
                            for (var _parentage_1 = __values(_parentage), _parentage_1_1 = _parentage_1.next(); !_parentage_1_1.done; _parentage_1_1 = _parentage_1.next()) {
                                var parent_1 = _parentage_1_1.value;
                                parent_1.remove(this);
                            }
                        }
                        catch (e_1_1) { e_1 = { error: e_1_1 }; }
                        finally {
                            try {
                                if (_parentage_1_1 && !_parentage_1_1.done && (_a = _parentage_1.return)) _a.call(_parentage_1);
                            }
                            finally { if (e_1) throw e_1.error; }
                        }
                    }
                    else {
                        _parentage.remove(this);
                    }
                }
                var initialFinalizer = this.initialTeardown;
                if (isFunction(initialFinalizer)) {
                    try {
                        initialFinalizer();
                    }
                    catch (e) {
                        errors = e instanceof UnsubscriptionError ? e.errors : [e];
                    }
                }
                var _finalizers = this._finalizers;
                if (_finalizers) {
                    this._finalizers = null;
                    try {
                        for (var _finalizers_1 = __values(_finalizers), _finalizers_1_1 = _finalizers_1.next(); !_finalizers_1_1.done; _finalizers_1_1 = _finalizers_1.next()) {
                            var finalizer = _finalizers_1_1.value;
                            try {
                                execFinalizer(finalizer);
                            }
                            catch (err) {
                                errors = errors !== null && errors !== void 0 ? errors : [];
                                if (err instanceof UnsubscriptionError) {
                                    errors = __spreadArray(__spreadArray([], __read(errors)), __read(err.errors));
                                }
                                else {
                                    errors.push(err);
                                }
                            }
                        }
                    }
                    catch (e_2_1) { e_2 = { error: e_2_1 }; }
                    finally {
                        try {
                            if (_finalizers_1_1 && !_finalizers_1_1.done && (_b = _finalizers_1.return)) _b.call(_finalizers_1);
                        }
                        finally { if (e_2) throw e_2.error; }
                    }
                }
                if (errors) {
                    throw new UnsubscriptionError(errors);
                }
            }
        };
        Subscription.prototype.add = function (teardown) {
            var _a;
            if (teardown && teardown !== this) {
                if (this.closed) {
                    execFinalizer(teardown);
                }
                else {
                    if (teardown instanceof Subscription) {
                        if (teardown.closed || teardown._hasParent(this)) {
                            return;
                        }
                        teardown._addParent(this);
                    }
                    (this._finalizers = (_a = this._finalizers) !== null && _a !== void 0 ? _a : []).push(teardown);
                }
            }
        };
        Subscription.prototype._hasParent = function (parent) {
            var _parentage = this._parentage;
            return _parentage === parent || (Array.isArray(_parentage) && _parentage.includes(parent));
        };
        Subscription.prototype._addParent = function (parent) {
            var _parentage = this._parentage;
            this._parentage = Array.isArray(_parentage) ? (_parentage.push(parent), _parentage) : _parentage ? [_parentage, parent] : parent;
        };
        Subscription.prototype._removeParent = function (parent) {
            var _parentage = this._parentage;
            if (_parentage === parent) {
                this._parentage = null;
            }
            else if (Array.isArray(_parentage)) {
                arrRemove(_parentage, parent);
            }
        };
        Subscription.prototype.remove = function (teardown) {
            var _finalizers = this._finalizers;
            _finalizers && arrRemove(_finalizers, teardown);
            if (teardown instanceof Subscription) {
                teardown._removeParent(this);
            }
        };
        Subscription.EMPTY = (function () {
            var empty = new Subscription();
            empty.closed = true;
            return empty;
        })();
        return Subscription;
    }());
    Subscription.EMPTY;
    function isSubscription(value) {
        return (value instanceof Subscription ||
            (value && 'closed' in value && isFunction(value.remove) && isFunction(value.add) && isFunction(value.unsubscribe)));
    }
    function execFinalizer(finalizer) {
        if (isFunction(finalizer)) {
            finalizer();
        }
        else {
            finalizer.unsubscribe();
        }
    }

    var config = {
        onUnhandledError: null,
        onStoppedNotification: null,
        Promise: undefined,
        useDeprecatedSynchronousErrorHandling: false,
        useDeprecatedNextContext: false,
    };

    var timeoutProvider = {
        setTimeout: function (handler, timeout) {
            var args = [];
            for (var _i = 2; _i < arguments.length; _i++) {
                args[_i - 2] = arguments[_i];
            }
            return setTimeout.apply(void 0, __spreadArray([handler, timeout], __read(args)));
        },
        clearTimeout: function (handle) {
            return (clearTimeout)(handle);
        },
        delegate: undefined,
    };

    function reportUnhandledError(err) {
        timeoutProvider.setTimeout(function () {
            {
                throw err;
            }
        });
    }

    function noop() { }

    function errorContext(cb) {
        {
            cb();
        }
    }

    var Subscriber = (function (_super) {
        __extends(Subscriber, _super);
        function Subscriber(destination) {
            var _this = _super.call(this) || this;
            _this.isStopped = false;
            if (destination) {
                _this.destination = destination;
                if (isSubscription(destination)) {
                    destination.add(_this);
                }
            }
            else {
                _this.destination = EMPTY_OBSERVER;
            }
            return _this;
        }
        Subscriber.create = function (next, error, complete) {
            return new SafeSubscriber(next, error, complete);
        };
        Subscriber.prototype.next = function (value) {
            if (this.isStopped) ;
            else {
                this._next(value);
            }
        };
        Subscriber.prototype.error = function (err) {
            if (this.isStopped) ;
            else {
                this.isStopped = true;
                this._error(err);
            }
        };
        Subscriber.prototype.complete = function () {
            if (this.isStopped) ;
            else {
                this.isStopped = true;
                this._complete();
            }
        };
        Subscriber.prototype.unsubscribe = function () {
            if (!this.closed) {
                this.isStopped = true;
                _super.prototype.unsubscribe.call(this);
                this.destination = null;
            }
        };
        Subscriber.prototype._next = function (value) {
            this.destination.next(value);
        };
        Subscriber.prototype._error = function (err) {
            try {
                this.destination.error(err);
            }
            finally {
                this.unsubscribe();
            }
        };
        Subscriber.prototype._complete = function () {
            try {
                this.destination.complete();
            }
            finally {
                this.unsubscribe();
            }
        };
        return Subscriber;
    }(Subscription));
    var _bind = Function.prototype.bind;
    function bind(fn, thisArg) {
        return _bind.call(fn, thisArg);
    }
    var ConsumerObserver = (function () {
        function ConsumerObserver(partialObserver) {
            this.partialObserver = partialObserver;
        }
        ConsumerObserver.prototype.next = function (value) {
            var partialObserver = this.partialObserver;
            if (partialObserver.next) {
                try {
                    partialObserver.next(value);
                }
                catch (error) {
                    handleUnhandledError(error);
                }
            }
        };
        ConsumerObserver.prototype.error = function (err) {
            var partialObserver = this.partialObserver;
            if (partialObserver.error) {
                try {
                    partialObserver.error(err);
                }
                catch (error) {
                    handleUnhandledError(error);
                }
            }
            else {
                handleUnhandledError(err);
            }
        };
        ConsumerObserver.prototype.complete = function () {
            var partialObserver = this.partialObserver;
            if (partialObserver.complete) {
                try {
                    partialObserver.complete();
                }
                catch (error) {
                    handleUnhandledError(error);
                }
            }
        };
        return ConsumerObserver;
    }());
    var SafeSubscriber = (function (_super) {
        __extends(SafeSubscriber, _super);
        function SafeSubscriber(observerOrNext, error, complete) {
            var _this = _super.call(this) || this;
            var partialObserver;
            if (isFunction(observerOrNext) || !observerOrNext) {
                partialObserver = {
                    next: (observerOrNext !== null && observerOrNext !== void 0 ? observerOrNext : undefined),
                    error: error !== null && error !== void 0 ? error : undefined,
                    complete: complete !== null && complete !== void 0 ? complete : undefined,
                };
            }
            else {
                var context_1;
                if (_this && config.useDeprecatedNextContext) {
                    context_1 = Object.create(observerOrNext);
                    context_1.unsubscribe = function () { return _this.unsubscribe(); };
                    partialObserver = {
                        next: observerOrNext.next && bind(observerOrNext.next, context_1),
                        error: observerOrNext.error && bind(observerOrNext.error, context_1),
                        complete: observerOrNext.complete && bind(observerOrNext.complete, context_1),
                    };
                }
                else {
                    partialObserver = observerOrNext;
                }
            }
            _this.destination = new ConsumerObserver(partialObserver);
            return _this;
        }
        return SafeSubscriber;
    }(Subscriber));
    function handleUnhandledError(error) {
        {
            reportUnhandledError(error);
        }
    }
    function defaultErrorHandler(err) {
        throw err;
    }
    var EMPTY_OBSERVER = {
        closed: true,
        next: noop,
        error: defaultErrorHandler,
        complete: noop,
    };

    var observable = (function () { return (typeof Symbol === 'function' && Symbol.observable) || '@@observable'; })();

    function identity(x) {
        return x;
    }

    function pipeFromArray(fns) {
        if (fns.length === 0) {
            return identity;
        }
        if (fns.length === 1) {
            return fns[0];
        }
        return function piped(input) {
            return fns.reduce(function (prev, fn) { return fn(prev); }, input);
        };
    }

    var Observable = (function () {
        function Observable(subscribe) {
            if (subscribe) {
                this._subscribe = subscribe;
            }
        }
        Observable.prototype.lift = function (operator) {
            var observable = new Observable();
            observable.source = this;
            observable.operator = operator;
            return observable;
        };
        Observable.prototype.subscribe = function (observerOrNext, error, complete) {
            var _this = this;
            var subscriber = isSubscriber(observerOrNext) ? observerOrNext : new SafeSubscriber(observerOrNext, error, complete);
            errorContext(function () {
                var _a = _this, operator = _a.operator, source = _a.source;
                subscriber.add(operator
                    ?
                        operator.call(subscriber, source)
                    : source
                        ?
                            _this._subscribe(subscriber)
                        :
                            _this._trySubscribe(subscriber));
            });
            return subscriber;
        };
        Observable.prototype._trySubscribe = function (sink) {
            try {
                return this._subscribe(sink);
            }
            catch (err) {
                sink.error(err);
            }
        };
        Observable.prototype.forEach = function (next, promiseCtor) {
            var _this = this;
            promiseCtor = getPromiseCtor(promiseCtor);
            return new promiseCtor(function (resolve, reject) {
                var subscriber = new SafeSubscriber({
                    next: function (value) {
                        try {
                            next(value);
                        }
                        catch (err) {
                            reject(err);
                            subscriber.unsubscribe();
                        }
                    },
                    error: reject,
                    complete: resolve,
                });
                _this.subscribe(subscriber);
            });
        };
        Observable.prototype._subscribe = function (subscriber) {
            var _a;
            return (_a = this.source) === null || _a === void 0 ? void 0 : _a.subscribe(subscriber);
        };
        Observable.prototype[observable] = function () {
            return this;
        };
        Observable.prototype.pipe = function () {
            var operations = [];
            for (var _i = 0; _i < arguments.length; _i++) {
                operations[_i] = arguments[_i];
            }
            return pipeFromArray(operations)(this);
        };
        Observable.prototype.toPromise = function (promiseCtor) {
            var _this = this;
            promiseCtor = getPromiseCtor(promiseCtor);
            return new promiseCtor(function (resolve, reject) {
                var value;
                _this.subscribe(function (x) { return (value = x); }, function (err) { return reject(err); }, function () { return resolve(value); });
            });
        };
        Observable.create = function (subscribe) {
            return new Observable(subscribe);
        };
        return Observable;
    }());
    function getPromiseCtor(promiseCtor) {
        var _a;
        return (_a = promiseCtor !== null && promiseCtor !== void 0 ? promiseCtor : config.Promise) !== null && _a !== void 0 ? _a : Promise;
    }
    function isObserver(value) {
        return value && isFunction(value.next) && isFunction(value.error) && isFunction(value.complete);
    }
    function isSubscriber(value) {
        return (value && value instanceof Subscriber) || (isObserver(value) && isSubscription(value));
    }

    function hasLift(source) {
        return isFunction(source === null || source === void 0 ? void 0 : source.lift);
    }
    function operate(init) {
        return function (source) {
            if (hasLift(source)) {
                return source.lift(function (liftedSource) {
                    try {
                        return init(liftedSource, this);
                    }
                    catch (err) {
                        this.error(err);
                    }
                });
            }
            throw new TypeError('Unable to lift unknown Observable type');
        };
    }

    function createOperatorSubscriber(destination, onNext, onComplete, onError, onFinalize) {
        return new OperatorSubscriber(destination, onNext, onComplete, onError, onFinalize);
    }
    var OperatorSubscriber = (function (_super) {
        __extends(OperatorSubscriber, _super);
        function OperatorSubscriber(destination, onNext, onComplete, onError, onFinalize, shouldUnsubscribe) {
            var _this = _super.call(this, destination) || this;
            _this.onFinalize = onFinalize;
            _this.shouldUnsubscribe = shouldUnsubscribe;
            _this._next = onNext
                ? function (value) {
                    try {
                        onNext(value);
                    }
                    catch (err) {
                        destination.error(err);
                    }
                }
                : _super.prototype._next;
            _this._error = onError
                ? function (err) {
                    try {
                        onError(err);
                    }
                    catch (err) {
                        destination.error(err);
                    }
                    finally {
                        this.unsubscribe();
                    }
                }
                : _super.prototype._error;
            _this._complete = onComplete
                ? function () {
                    try {
                        onComplete();
                    }
                    catch (err) {
                        destination.error(err);
                    }
                    finally {
                        this.unsubscribe();
                    }
                }
                : _super.prototype._complete;
            return _this;
        }
        OperatorSubscriber.prototype.unsubscribe = function () {
            var _a;
            if (!this.shouldUnsubscribe || this.shouldUnsubscribe()) {
                var closed_1 = this.closed;
                _super.prototype.unsubscribe.call(this);
                !closed_1 && ((_a = this.onFinalize) === null || _a === void 0 ? void 0 : _a.call(this));
            }
        };
        return OperatorSubscriber;
    }(Subscriber));

    var EMPTY = new Observable(function (subscriber) { return subscriber.complete(); });

    function take(count) {
        return count <= 0
            ?
                function () { return EMPTY; }
            : operate(function (source, subscriber) {
                var seen = 0;
                source.subscribe(createOperatorSubscriber(subscriber, function (value) {
                    if (++seen <= count) {
                        subscriber.next(value);
                        if (count <= seen) {
                            subscriber.complete();
                        }
                    }
                }));
            });
    }

    const popoverWidgetId = 'craftercms.components.aiassistant.ChatPopover';
    const aiAssistantClosedMessageId = 'craftercms.aiassistant.PanelClosed';
    const openAiAssistantMessageId = 'craftercms.aiassistant.OpenPanel';
    /*
    import { EmptyStateOption } from './AiAssistant';

    export const CrafterQResultMessageId = 'craftercms.aiassistant.CrafterQResult';

    // Legacy commented defaults removed — configure <llmModel> / <imageModel> in ui.xml (no server image-model fallback).

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
    export const aiAssistantEmptyStateOptionsChat: Array<EmptyStateOption> = [
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

    const BASE_CONFIG = {
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
    const craftercms = window.craftercms;
    const tinymce = window.tinymce;
    const xb = craftercms?.xb;
    const isXb = Boolean(xb);
    const pluginManager = tinymce.util.Tools.resolve('tinymce.PluginManager');
    const getContent = (editor) => {
        return editor.getContent({ format: 'text' });
    };
    const getSelection = (editor) => {
        return editor.selection.getContent({ format: 'text' });
    };
    const handleChatActionClick = (editor, id, content) => {
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
                    const userName = "dave"; //createUsername(craftercms.getStore().getState().user);
                    const container = document.createElement('div');
                    const root = craftercms.libs.ReactDOMClient.createRoot(container);
                    const AiAssistantPopover = plugin.widgets[popoverWidgetId]; // Same as craftercms.utils.constants.components.get('...');
                    const CrafterRoot = craftercms.utils.constants.components.get('craftercms.components.CrafterCMSNextBridge');
                    root.render(jsxRuntime.jsx(CrafterRoot, { children: jsxRuntime.jsx(AiAssistantPopover, { open: true, onClose: (e) => {
                                e.preventDefault();
                                e.stopPropagation();
                                root.unmount();
                                container.remove();
                            }, ...config.AiAssistantPopoverProps, AiAssistantProps: {
                                userName,
                                emptyStateOptions: config.emptyStateOptions,
                                initialMessages: messages,
                                extraActions: [{ label: 'Insert', id: 'insert' }],
                                onExtraActionClick: ((e, id, content, api) => {
                                    handleChatActionClick(editor, id, content);
                                })
                            } }) }));
                });
            }
            else {
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
    pluginManager.add('craftercms_aiassistant', function (editor) {
        const configArg = editor.getParam('craftercms_aiassistant');
        const mergedStrings = {
            ...BASE_CONFIG.strings,
            ...configArg?.strings
        };
        const instanceConfig = {
            ...BASE_CONFIG,
            ...configArg,
            strings: {
                ...mergedStrings,
                openAiAssistant: mergedStrings.openAiAssistant ??
                    mergedStrings.crafterqDialog ??
                    'Open AI Assistant',
                aiAssistantShortcuts: mergedStrings.aiAssistantShortcuts ??
                    mergedStrings.crafterqShortcuts ??
                    'AI Shortcuts'
            }
        };
        const userOpen = configArg?.onOpenAiAssistant ?? configArg?.oncrafterqDialog;
        if (!userOpen || !configArg?.onShortcutClick) {
            const defaultHandler = createDefaultHandler(instanceConfig);
            instanceConfig.onOpenAiAssistant = defaultHandler;
            instanceConfig.onShortcutClick = defaultHandler;
        }
        else {
            instanceConfig.onOpenAiAssistant = userOpen;
            instanceConfig.onShortcutClick = configArg.onShortcutClick;
        }
        editor.ui.registry.addButton('aiAssistantOpen', {
            icon: 'ai',
            tooltip: instanceConfig.strings.openAiAssistant,
            onAction(api) {
                const content = getSelection(editor).trim() || getContent(editor);
                const messages = [...instanceConfig.prependMessages].map((item) => ({
                    ...item,
                    content: item.content.replace('{context}', content)
                }));
                const selection = getSelection(editor);
                if (selection) {
                    messages.push({ role: 'system', content: `Context: ${selection}` });
                }
                instanceConfig.onOpenAiAssistant(editor, api, messages);
            }
        });
        editor.ui.registry.addMenuButton('crafterqshortcuts', {
            icon: 'ai-prompt',
            tooltip: instanceConfig.strings.aiAssistantShortcuts,
            fetch(callback) {
                const onAction = (api, item) => {
                    const content = getSelection(editor).trim() || getContent(editor);
                    const messages = [...instanceConfig.prependMessages, ...(item.messages ?? [])].map((item) => ({
                        ...item,
                        content: item.content.replace('{context}', content)
                    }));
                    instanceConfig.onShortcutClick(editor, api, messages);
                };
                const mapper = (shortcut) => {
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
                const messages = [...instanceConfig.prependMessages].map((item) => ({
                    ...item,
                    content: item.content.replace('{context}', content)
                }));
                instanceConfig.onOpenAiAssistant(editor, api, messages);
            },
            onItemAction(api, item) {
                const content = getSelection(editor).trim() || getContent(editor);
                const messages = [...instanceConfig.prependMessages, ...(item.messages ?? [])].map((item) => ({
                    ...item,
                    content: item.content.replace('{context}', content)
                }));
                instanceConfig.onShortcutClick(editor, api, messages);
            }
        });
        return {};
    });

})(craftercms.libs?.reactJsxRuntime);
