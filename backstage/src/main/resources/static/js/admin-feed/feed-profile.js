(function(global) {
    'use strict';

    function ProfileLoader(options) {
        this.options = options || {};
        this.token = 0;
        this.request = null;
        this.state = emptyState();
    }

    ProfileLoader.prototype.load = function(query, type) {
        this.cancel();
        this.state = emptyState();
        this.state.query = Object.assign({}, query || {});
        this.state.type = type || '';
        this.state.loading = true;
        var token = ++this.token;
        notify(this.options.onReset, this.snapshot());
        this.loadNext(token);
    };

    ProfileLoader.prototype.loadNext = function(token) {
        var loader = this;
        if (token !== this.token || !this.state.loading || !this.state.hasMore) {
            return;
        }
        var pageNo = this.state.nextPage;
        var params = Object.assign({}, this.state.query, {
            type: this.state.type,
            pageNo: pageNo,
            pageSize: Number(this.options.pageSize || 100)
        });
        this.request = this.options.request(params);
        this.request.done(function(response) {
            if (token !== loader.token) {
                return;
            }
            if (!(response && response.resCode === '000001')) {
                loader.finishWithError('No works');
                return;
            }
            var record = response.record || {};
            var content = Array.isArray(record.content) ? record.content : [];
            var added = appendUnique(loader.state.items, content, loader.options.keyOf);
            loader.state.nextPage = pageNo + 1;
            loader.state.hasMore = content.length > 0 && record.last !== true;
            loader.state.loading = loader.state.hasMore;
            notify(loader.options.onPage, loader.snapshot(), added);
            if (loader.state.hasMore) {
                setTimeout(function() {
                    loader.loadNext(token);
                }, 0);
            } else {
                notify(loader.options.onComplete, loader.snapshot());
            }
        }).fail(function(xhr, status) {
            if (token !== loader.token || status === 'abort') {
                return;
            }
            loader.finishWithError('Load failed');
        });
    };

    ProfileLoader.prototype.finishWithError = function(message) {
        this.state.loading = false;
        this.state.error = message || 'Load failed';
        notify(this.options.onError, this.snapshot());
    };

    ProfileLoader.prototype.cancel = function() {
        this.token++;
        if (this.request && typeof this.request.abort === 'function') {
            try { this.request.abort(); } catch (e) {}
        }
        this.request = null;
        this.state.loading = false;
        return this.snapshot();
    };

    ProfileLoader.prototype.snapshot = function() {
        return Object.assign({}, this.state, {
            query: Object.assign({}, this.state.query || {}),
            items: this.state.items.slice()
        });
    };

    function emptyState() {
        return {
            query: {},
            type: '',
            items: [],
            nextPage: 0,
            hasMore: true,
            loading: false,
            error: ''
        };
    }

    function appendUnique(target, incoming, keyOf) {
        var known = {};
        var added = [];
        target.forEach(function(item) {
            known[readKey(item, keyOf)] = true;
        });
        incoming.forEach(function(item) {
            var key = readKey(item, keyOf);
            if (!key || known[key]) {
                return;
            }
            known[key] = true;
            target.push(item);
            added.push(item);
        });
        return added;
    }

    function readKey(item, keyOf) {
        if (typeof keyOf === 'function') {
            return String(keyOf(item) || '');
        }
        return String((item && (item.mediaKey || item.id || item.videoid)) || '');
    }

    function notify(callback) {
        if (typeof callback !== 'function') {
            return;
        }
        callback.apply(null, Array.prototype.slice.call(arguments, 1));
    }

    global.AdminFeed = global.AdminFeed || {};
    global.AdminFeed.ProfileLoader = ProfileLoader;
})(window);
