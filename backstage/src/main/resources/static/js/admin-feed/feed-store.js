(function(global) {
    'use strict';

    function FeedStore(initialState) {
        this.state = Object.assign({
            page: 0,
            isLoading: false,
            hasMore: true,
            items: [],
            currentIndex: -1,
            renderedCenterIndex: -1,
            switching: false,
            scrollDirection: 'down',
            pendingAutoNextIndex: -1,
            authorContext: null,
            authorContextTargetKey: '',
            profileQuery: null,
            profileType: '',
            profileWorks: []
        }, initialState || {});
    }

    FeedStore.prototype.bindLegacyGlobals = function(mapping) {
        var store = this;
        Object.keys(mapping || {}).forEach(function(globalName) {
            var stateName = mapping[globalName];
            Object.defineProperty(global, globalName, {
                configurable: true,
                enumerable: true,
                get: function() {
                    return store.state[stateName];
                },
                set: function(value) {
                    store.state[stateName] = value;
                }
            });
        });
        return this;
    };

    FeedStore.prototype.set = function(values) {
        Object.assign(this.state, values || {});
        return this;
    };

    FeedStore.prototype.replaceItems = function(items) {
        this.state.items = Array.isArray(items) ? items.slice() : [];
        return this.state.items;
    };

    FeedStore.prototype.snapshot = function() {
        return Object.assign({}, this.state, {
            items: this.state.items.slice(),
            profileWorks: this.state.profileWorks.slice()
        });
    };

    global.AdminFeed = global.AdminFeed || {};
    global.AdminFeed.FeedStore = FeedStore;
})(window);
