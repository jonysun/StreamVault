(function(global) {
    'use strict';

    function normalized(value) {
        return value == null ? '' : String(value);
    }

    function changedOverrides(original, current, keys) {
        var result = {};
        (keys || Object.keys(current || {})).forEach(function(key) {
            if (normalized(current && current[key]) !== normalized(original && original[key])) {
                result[key] = current[key] === '' ? null : current[key];
            }
        });
        return result;
    }

    function removeByMediaKey(items, mediaKey, keyOf) {
        var source = Array.isArray(items) ? items : [];
        var getKey = typeof keyOf === 'function' ? keyOf : function(item) {
            return item && item.mediaKey ? String(item.mediaKey) : '';
        };
        return source.filter(function(item) {
            return getKey(item) !== mediaKey;
        });
    }

    function indexAfterRemoval(previousIndex, remainingLength) {
        if (remainingLength <= 0) {
            return -1;
        }
        return Math.max(0, Math.min(Number(previousIndex) || 0, remainingLength - 1));
    }

    global.AdminFeed = global.AdminFeed || {};
    global.AdminFeed.Management = {
        changedOverrides: changedOverrides,
        removeByMediaKey: removeByMediaKey,
        indexAfterRemoval: indexAfterRemoval
    };
})(window);
