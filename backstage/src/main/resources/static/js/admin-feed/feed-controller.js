(function(global) {
    'use strict';

    function FeedController(store, hooks) {
        this.store = store;
        this.hooks = hooks || {};
    }

    FeedController.prototype.activateAuthorWork = function(profileSnapshot, mediaKey, context) {
        var items = profileSnapshot && Array.isArray(profileSnapshot.items)
            ? profileSnapshot.items.slice() : [];
        if (!items.length) {
            return false;
        }
        var keyOf = this.hooks.keyOf || function(item) {
            return String((item && (item.mediaKey || item.id)) || '');
        };
        var targetIndex = 0;
        for (var i = 0; i < items.length; i++) {
            if (keyOf(items[i]) === mediaKey) {
                targetIndex = i;
                break;
            }
        }
        if (typeof this.hooks.beforeReset === 'function') {
            this.hooks.beforeReset();
        }
        this.store.set({
            items: items,
            page: Math.max(0, Number(profileSnapshot.nextPage || 1) - 1),
            hasMore: !!profileSnapshot.hasMore,
            authorContext: Object.assign({}, context || {}, {
                nextPage: Number(profileSnapshot.nextPage || 0)
            }),
            authorContextTargetKey: '',
            currentIndex: -1,
            renderedCenterIndex: -1,
            switching: false
        });
        if (typeof this.hooks.afterReset === 'function') {
            this.hooks.afterReset(items, targetIndex);
        }
        return true;
    };

    global.AdminFeed = global.AdminFeed || {};
    global.AdminFeed.FeedController = FeedController;
})(window);
