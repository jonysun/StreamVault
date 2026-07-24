const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

class ClassList {
    constructor() {
        this.values = new Set();
    }
    add(value) { this.values.add(value); }
    remove(value) { this.values.delete(value); }
    contains(value) { return this.values.has(value); }
}

function createNode(tagName, className) {
    const node = {
        tagName: String(tagName || '').toUpperCase(),
        className: className || '',
        classList: new ClassList(),
        attributes: {},
        children: [],
        parentNode: null,
        readyState: 0,
        listeners: {},
        hidden: false,
        setAttribute(name, value) { this.attributes[name] = String(value); },
        getAttribute(name) { return this.attributes[name] || null; },
        removeAttribute(name) { delete this.attributes[name]; },
        addEventListener(name, callback) {
            this.listeners[name] = this.listeners[name] || [];
            this.listeners[name].push(callback);
        },
        dispatch(name) {
            (this.listeners[name] || []).forEach((callback) => callback.call(this));
        },
        appendChild(child) {
            if (child.parentNode) {
                child.parentNode.children = child.parentNode.children.filter((item) => item !== child);
            }
            child.parentNode = this;
            this.children.push(child);
            return child;
        },
        closest(selector) {
            let current = this;
            while (current) {
                if (selector === '.feed-video-host' && current.className === 'feed-video-host') {
                    return current;
                }
                current = current.parentNode;
            }
            return null;
        }
    };
    return node;
}

function createItem(index, key) {
    const host = createNode('div', 'feed-video-host');
    host.setAttribute('data-media-key', key);
    const item = createNode('div', 'feed-item');
    item.setAttribute('data-abs-index', index);
    item.querySelector = (selector) => selector === '.feed-video-host' ? host : null;
    item.appendChild(host);
    return { item, host };
}

function loadModules() {
    const body = createNode('body', '');
    const nodesById = {};
    const document = {
        body,
        createElement: (tag) => createNode(tag, ''),
        getElementById: (id) => nodesById[id] || null
    };
    const window = { document, setTimeout, clearTimeout };
    window.window = window;
    const context = vm.createContext({ window, document, setTimeout, clearTimeout, console });
    const base = path.resolve(__dirname, '../../main/resources/static/js/admin-feed');
    ['feed-store.js', 'feed-player-pool.js', 'feed-profile.js', 'feed-controller.js', 'feed-management.js'].forEach((file) => {
        vm.runInContext(fs.readFileSync(path.join(base, file), 'utf8'), context, { filename: file });
    });
    return window;
}

function testPlayerReuse(window) {
    const mounted = [];
    const pool = new window.AdminFeed.PlayerPool({
        maxPlayers: 4,
        mount(video, item, index) {
            const host = item.querySelector('.feed-video-host');
            host.appendChild(video);
            video.setAttribute('data-feed-index', index);
            video.setAttribute('data-bound-src', 'source-' + host.getAttribute('data-media-key'));
            mounted.push(video);
        },
        release() {}
    });
    let firstWindow = [createItem(0, 'a'), createItem(1, 'b'), createItem(2, 'c')];
    pool.mountWindow(firstWindow.map((entry) => entry.item), 1, 'down');
    const preloadedC = pool.players.find((video) => video.getAttribute('data-media-key') === 'c');
    preloadedC.readyState = 2;
    preloadedC.dispatch('loadeddata');
    assert.strictEqual(firstWindow[2].host.classList.contains('feed-video-ready'), true);

    pool.stageAll();
    const secondWindow = [createItem(1, 'b'), createItem(2, 'c'), createItem(3, 'd')];
    pool.mountWindow(secondWindow.map((entry) => entry.item), 2, 'down');
    const currentC = pool.players.find((video) => video.getAttribute('data-media-key') === 'c');
    assert.strictEqual(currentC, preloadedC, 'the preloaded adjacent player must become current');
    assert.strictEqual(currentC.getAttribute('data-bound-src'), 'source-c');
    assert.strictEqual(secondWindow[1].host.classList.contains('feed-video-ready'), true);
    assert.ok(mounted.length >= 6);
}

function testIncrementalProfileActivation(window) {
    const requests = [];
    function fakeRequest() {
        const handlers = {};
        const request = {
            done(callback) { handlers.done = callback; return request; },
            fail(callback) { handlers.fail = callback; return request; },
            abort() { handlers.aborted = true; },
            resolve(value) { handlers.done(value); }
        };
        requests.push(request);
        return request;
    }

    let firstPageSnapshot = null;
    const loader = new window.AdminFeed.ProfileLoader({
        pageSize: 100,
        keyOf: (item) => item.mediaKey,
        request: fakeRequest,
        onPage(snapshot) { firstPageSnapshot = snapshot; }
    });
    loader.load({ authoruid: 'author-1' }, '');
    requests[0].resolve({
        resCode: '000001',
        record: { content: [{ mediaKey: 'a' }, { mediaKey: 'b' }], last: false }
    });
    assert.strictEqual(firstPageSnapshot.items.map((item) => item.mediaKey).join(','), 'a,b');
    assert.strictEqual(firstPageSnapshot.hasMore, true);
    assert.strictEqual(firstPageSnapshot.nextPage, 1);

    const clickableSnapshot = loader.cancel();
    const store = new window.AdminFeed.FeedStore();
    let selectedIndex = -1;
    const controller = new window.AdminFeed.FeedController(store, {
        keyOf: (item) => item.mediaKey,
        beforeReset() {},
        afterReset(items, index) { selectedIndex = index; }
    });
    assert.strictEqual(controller.activateAuthorWork(clickableSnapshot, 'b', {
        query: { authoruid: 'author-1' },
        pageSize: 100
    }), true);
    assert.strictEqual(selectedIndex, 1);
    assert.strictEqual(store.state.items.length, 2);
    assert.strictEqual(store.state.hasMore, true);
    assert.strictEqual(store.state.page, 0);
}

function extractFunction(source, name) {
    const start = source.indexOf('function ' + name + '(');
    assert.ok(start >= 0, 'missing function ' + name);
    const bodyStart = source.indexOf('{', start);
    let depth = 0;
    for (let i = bodyStart; i < source.length; i += 1) {
        if (source[i] === '{') depth += 1;
        if (source[i] === '}') depth -= 1;
        if (depth === 0) return source.slice(start, i + 1);
    }
    throw new Error('unterminated function ' + name);
}

function testFeedOriginalUrlNormalization() {
    const html = fs.readFileSync(
        path.resolve(__dirname, '../../main/resources/templates/admin/index.html'),
        'utf8'
    );
    const names = ['safeExternalUrl', 'isDouyinFeedPlatform', 'canonicalFeedAuthorUid', 'buildFeedOriginalUrl'];
    const context = vm.createContext({ Array, encodeURIComponent });
    vm.runInContext(names.map((name) => extractFunction(html, name)).join('\n'), context);

    assert.strictEqual(context.buildFeedOriginalUrl({
        type: 'graphic',
        platform: 'douyin',
        videoid: '7622696915705286079',
        authoruid: '84583932458',
        secuid: 'MS4wLjABAAAAstable',
        sourceurl: 'https://www.douyin.com/user/84583932458?modal_id=7622696915705286079'
    }), 'https://www.douyin.com/user/MS4wLjABAAAAstable?modal_id=7622696915705286079');
    assert.strictEqual(context.buildFeedOriginalUrl({
        type: 'graphic',
        platform: 'douyin',
        videoid: '7622696915705286079',
        authoruid: '84583932458',
        sourceurl: 'https://www.douyin.com/user/84583932458?modal_id=7622696915705286079'
    }), '');
    assert.strictEqual(context.buildFeedOriginalUrl({
        type: 'video',
        platform: 'douyin',
        videoid: '7622696915705286079',
        slides: []
    }), 'https://www.douyin.com/video/7622696915705286079');
}

function testManagementUtilities(window) {
    const management = window.AdminFeed.Management;
    assert.deepStrictEqual(JSON.parse(JSON.stringify(management.changedOverrides(
        { title: 'old', description: 'same', favorite: '0' },
        { title: 'new', description: 'same', favorite: '' },
        ['title', 'description', 'favorite']
    ))), { title: 'new', favorite: null });
    assert.deepStrictEqual(
        management.removeByMediaKey([{ mediaKey: 'video:1' }, { mediaKey: 'graphic:2' }], 'video:1')
            .map((item) => item.mediaKey),
        ['graphic:2']
    );
    assert.strictEqual(management.indexAfterRemoval(3, 2), 1);
    assert.strictEqual(management.indexAfterRemoval(0, 0), -1);
}

function testFeedAuthorActionRendering() {
    const html = fs.readFileSync(
        path.resolve(__dirname, '../../main/resources/templates/admin/index.html'),
        'utf8'
    );
    const names = [
        'isDouyinFeedPlatform',
        'canonicalFeedAuthorUid',
        'authorInitial',
        'escapeHtml',
        'buildFeedAuthorActionHtml'
    ];
    const context = vm.createContext({ Array, encodeURIComponent });
    vm.runInContext(names.map((name) => extractFunction(html, name)).join('\n'), context);

    const pending = context.buildFeedAuthorActionHtml({
        platform: 'douyin',
        author: '待修复作者',
        authoravatar: 'https://img.example/avatar.jpg'
    }, false, '待修复作者');
    assert.match(pending, /作者 UID 待修复/);
    assert.match(pending, /disabled/);

    const normal = context.buildFeedAuthorActionHtml({
        platform: 'douyin',
        author: '作者',
        authoruid: 'MS4wLjABAAAAstable'
    }, false, '作者');
    assert.match(normal, /作者主页/);
    assert.doesNotMatch(normal, /disabled/);

    assert.strictEqual(context.buildFeedAuthorActionHtml({ platform: 'douyin' }, false, ''), '');
}

function testTemplateHasUniqueTopLevelFunctions() {
    const html = fs.readFileSync(
        path.resolve(__dirname, '../../main/resources/templates/admin/index.html'),
        'utf8'
    );
    const counts = new Map();
    const declaration = /^    function\s+([A-Za-z_$][\w$]*)\s*\(/gm;
    let match;
    while ((match = declaration.exec(html)) !== null) {
        counts.set(match[1], (counts.get(match[1]) || 0) + 1);
    }
    const duplicates = Array.from(counts.entries())
        .filter((entry) => entry[1] > 1)
        .map((entry) => entry[0]);
    assert.deepStrictEqual(duplicates, [], 'duplicate top-level functions: ' + duplicates.join(', '));
}

function testTemplateInlineScriptsParseAndExposeManagementActions() {
    const html = fs.readFileSync(
        path.resolve(__dirname, '../../main/resources/templates/admin/index.html'),
        'utf8'
    );
    const scripts = Array.from(html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi))
        .map((match) => match[1])
        .filter((source) => source.trim());
    scripts.forEach((source, index) => {
        new vm.Script(source, { filename: 'admin-index-inline-' + index + '.js' });
    });
    assert.match(html, /id="feedEditBtn"/);
    assert.match(html, /id="feedDeleteBtn"/);
    assert.match(html, /id="feedAuthorDeleteProfileBtn"/);
    assert.doesNotMatch(html, /id="feedMuteBtn"/);
}

const window = loadModules();
testPlayerReuse(window);
testIncrementalProfileActivation(window);
testManagementUtilities(window);
testFeedOriginalUrlNormalization();
testFeedAuthorActionRendering();
testTemplateHasUniqueTopLevelFunctions();
testTemplateInlineScriptsParseAndExposeManagementActions();
console.log('admin-feed module tests passed');
