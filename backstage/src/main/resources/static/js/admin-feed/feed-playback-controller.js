(function(global) {
    'use strict';

    function FeedPlaybackController(options) {
        this.options = options || {};
        this.video = null;
        this.staging = null;
    }

    FeedPlaybackController.prototype.ensure = function() {
        if (!this.video) {
            this.video = document.createElement('video');
            this.video.className = 'feed-video hide-controls';
            this.video.setAttribute('preload', 'auto');
            this.video.setAttribute('playsinline', '');
            this.video.setAttribute('webkit-playsinline', '');
            this.video._adminFeedReady = false;
            var controller = this;
            ['loadeddata', 'canplay', 'playing'].forEach(function(eventName) {
                controller.video.addEventListener(eventName, function() {
                    if (controller.video.readyState >= 2) {
                        controller.markReady(controller.video);
                    }
                });
            });
        }
        return [this.video];
    };

    FeedPlaybackController.prototype.ensureStaging = function() {
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

    FeedPlaybackController.prototype.stageAll = function() {
        if (!this.video) {
            return;
        }
        this.video._playRequestToken = (this.video._playRequestToken || 0) + 1;
        try { this.video.pause(); } catch (error) {}
        var staging = this.ensureStaging();
        if (this.video.parentNode && this.video.parentNode !== staging) {
            staging.appendChild(this.video);
        }
    };

    FeedPlaybackController.prototype.markLoading = function(video) {
        if (!video) {
            return;
        }
        video._adminFeedReady = false;
        var host = video.parentNode;
        if (host && host.classList) {
            host.classList.remove('feed-video-ready');
        }
    };

    FeedPlaybackController.prototype.markReady = function(video) {
        if (!video) {
            return;
        }
        video._adminFeedReady = true;
        var host = video.parentNode;
        if (host && host.classList) {
            host.classList.add('feed-video-ready');
        }
    };

    FeedPlaybackController.prototype.mountWindow = function(items, activeIndex) {
        var activeItem = null;
        (items || []).some(function(item) {
            if (Number(item.getAttribute('data-abs-index') || '-1') === activeIndex) {
                activeItem = item;
                return true;
            }
            return false;
        });
        var video = this.ensure()[0];
        var host = activeItem && activeItem.getAttribute('data-feed-type') === 'video'
            ? activeItem.querySelector('.feed-video-host') : null;
        if (!host) {
            this.stageAll();
            if (typeof this.options.release === 'function') {
                this.options.release(video);
            }
            return [video];
        }
        video._poolRole = 'current';
        if (typeof this.options.mount === 'function') {
            this.options.mount(video, activeItem, activeIndex, true);
        }
        return [video];
    };

    global.AdminFeed = global.AdminFeed || {};
    global.AdminFeed.FeedPlaybackController = FeedPlaybackController;
})(window);
