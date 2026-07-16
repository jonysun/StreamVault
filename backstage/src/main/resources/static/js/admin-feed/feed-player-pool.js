(function(global) {
    'use strict';

    function PlayerPool(options) {
        this.options = options || {};
        this.maxPlayers = Math.max(3, Number(this.options.maxPlayers || 4));
        this.players = [];
        this.staging = null;
    }

    PlayerPool.prototype.ensure = function() {
        while (this.players.length < this.maxPlayers) {
            this.players.push(this.createPlayer());
        }
        return this.players;
    };

    PlayerPool.prototype.createPlayer = function() {
        var pool = this;
        var video = document.createElement('video');
        video.className = 'feed-video hide-controls';
        video.setAttribute('preload', 'auto');
        video.setAttribute('playsinline', '');
        video.setAttribute('webkit-playsinline', '');
        video._adminFeedReady = false;
        ['loadeddata', 'canplay', 'playing'].forEach(function(eventName) {
            video.addEventListener(eventName, function() {
                if (video.readyState >= 2) {
                    pool.markReady(video);
                }
            });
        });
        return video;
    };

    PlayerPool.prototype.ensureStaging = function() {
        if (this.staging && this.staging.parentNode) {
            return this.staging;
        }
        var staging = document.getElementById('feedPlayerStaging');
        if (!staging) {
            staging = document.createElement('div');
            staging.id = 'feedPlayerStaging';
            staging.hidden = true;
            document.body.appendChild(staging);
        }
        this.staging = staging;
        return staging;
    };

    PlayerPool.prototype.stageAll = function() {
        var staging = this.ensureStaging();
        this.ensure().forEach(function(video) {
            if (video.parentNode && video.parentNode !== staging) {
                staging.appendChild(video);
            }
        });
    };

    PlayerPool.prototype.markLoading = function(video) {
        if (!video) {
            return;
        }
        video._adminFeedReady = false;
        var host = video.closest ? video.closest('.feed-video-host') : video.parentNode;
        if (host && host.classList) {
            host.classList.remove('feed-video-ready');
        }
    };

    PlayerPool.prototype.markReady = function(video) {
        if (!video) {
            return;
        }
        video._adminFeedReady = true;
        var host = video.closest ? video.closest('.feed-video-host') : video.parentNode;
        if (host && host.classList) {
            host.classList.add('feed-video-ready');
        }
    };

    PlayerPool.prototype.mountWindow = function(items, activeIndex, direction) {
        var pool = this;
        var players = this.ensure();
        var targets = [];
        (items || []).forEach(function(item) {
            var host = item.querySelector('.feed-video-host');
            if (!host) {
                return;
            }
            targets.push({
                item: item,
                host: host,
                index: Number(item.getAttribute('data-abs-index') || '-1'),
                key: host.getAttribute('data-media-key') || ''
            });
        });
        targets.sort(function(left, right) {
            if (left.index === activeIndex) return -1;
            if (right.index === activeIndex) return 1;
            var leftDistance = Math.abs(left.index - activeIndex);
            var rightDistance = Math.abs(right.index - activeIndex);
            if (leftDistance !== rightDistance) return leftDistance - rightDistance;
            return direction === 'up' ? right.index - left.index : left.index - right.index;
        });

        var used = [];
        targets.forEach(function(target) {
            var player = findPlayer(players, used, function(candidate) {
                return target.key && candidate.getAttribute('data-media-key') === target.key;
            });
            if (!player) {
                player = findPlayer(players, used, function(candidate) {
                    return Number(candidate.getAttribute('data-feed-index') || '-1') === target.index;
                });
            }
            if (!player) {
                player = findPlayer(players, used, function(candidate) {
                    return !candidate.getAttribute('data-bound-src');
                });
            }
            if (!player) {
                player = findFarthestPlayer(players, used, activeIndex);
            }
            if (!player) {
                return;
            }
            used.push(player);
            player._poolRole = target.index === activeIndex ? 'current'
                : (target.index < activeIndex ? 'prev' : 'next');
            player.setAttribute('data-media-key', target.key);
            if (typeof pool.options.mount === 'function') {
                pool.options.mount(player, target.item, target.index, target.index === activeIndex);
            }
            if (player._adminFeedReady) {
                target.host.classList.add('feed-video-ready');
            }
        });

        players.forEach(function(player) {
            if (used.indexOf(player) >= 0) {
                return;
            }
            if (typeof pool.options.release === 'function') {
                pool.options.release(player, 1200);
            }
        });
        return players;
    };

    function findPlayer(players, used, predicate) {
        for (var i = 0; i < players.length; i++) {
            if (used.indexOf(players[i]) < 0 && predicate(players[i])) {
                return players[i];
            }
        }
        return null;
    }

    function findFarthestPlayer(players, used, activeIndex) {
        var candidate = null;
        var distance = -1;
        players.forEach(function(player) {
            if (used.indexOf(player) >= 0) {
                return;
            }
            var index = Number(player.getAttribute('data-feed-index') || '-999999');
            var currentDistance = Math.abs(index - activeIndex);
            if (!candidate || currentDistance > distance) {
                candidate = player;
                distance = currentDistance;
            }
        });
        return candidate;
    }

    global.AdminFeed = global.AdminFeed || {};
    global.AdminFeed.PlayerPool = PlayerPool;
})(window);
