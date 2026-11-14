/*
 * Local-network co-op shim.
 *
 * The bundled game pairs two phones with a code, builds an RTCPeerConnection with
 * `iceServers: []` and expects ICE to connect over the local network. Two things can stop
 * that from ever leaving "Connecting...":
 *
 *   1. Chromium can hide host candidates behind random mDNS names (`<uuid>.local`). Chrome on
 *      Android cannot resolve those names, so the other phone never gets a usable address.
 *      This shim rewrites its own mDNS candidates to the phone's real Wi-Fi address before
 *      the game serialises the local description into the pairing code. Chromium itself
 *      keeps the untouched description, so ICE is unaffected locally.
 *   2. The router blocks traffic between Wi-Fi clients (AP/client isolation). No app can fix
 *      that, but the shim reports the stall so the UI can explain it instead of hanging.
 *
 * Everything here is additive: with raw IPv4 candidates, or with a connection that works,
 * the game sees the exact same objects it would without the shim.
 */
(function () {
    'use strict';
    if (window.__igiLanShim) return;
    window.__igiLanShim = true;

    var bridge = window.IgiLan || null;

    function log(message) {
        var line = '[igi-lan] ' + message;
        try { if (bridge && bridge.log) bridge.log(line); } catch (e) { }
        try { console.log(line); } catch (e) { }
    }

    var Native = window.RTCPeerConnection;
    if (typeof Native !== 'function') {
        log('RTCPeerConnection is missing in this WebView');
        return;
    }

    var addresses = [];
    try {
        if (bridge && bridge.localIpv4) addresses = JSON.parse(bridge.localIpv4()) || [];
    } catch (e) {
        log('localIpv4() failed: ' + e);
    }

    var MDNS_NAME = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.local$/i;
    // "a=candidate:<foundation> <component> <transport> <priority> <address> <port> typ <type> ..."
    var CANDIDATE_LINE = /^(a=candidate:\S+ \d+ \S+ \d+ )(\S+)( \d+ typ \S+.*)$/i;

    function siteLocal(list) {
        var patterns = [/^192\.168\./, /^10\./, /^172\.(1[6-9]|2\d|3[01])\./];
        for (var p = 0; p < patterns.length; p++) {
            for (var i = 0; i < list.length; i++) {
                if (patterns[p].test(list[i])) return list[i];
            }
        }
        return list.length ? list[0] : null;
    }

    var localIp = siteLocal(addresses);
    log('local addresses=' + JSON.stringify(addresses) + ' preferred=' + localIp);

    var localDescriptionGetter = null;
    try {
        var proto = Native.prototype;
        while (proto && !localDescriptionGetter) {
            var descriptor = Object.getOwnPropertyDescriptor(proto, 'localDescription');
            if (descriptor && descriptor.get) localDescriptionGetter = descriptor.get;
            proto = Object.getPrototypeOf(proto);
        }
    } catch (e) {
        log('localDescription getter unavailable: ' + e);
    }

    function rewriteSdp(sdp) {
        if (!localIp || !sdp || sdp.indexOf('.local') === -1) return null;
        var lines = sdp.split(/\r\n|\r|\n/);
        var hits = 0;
        for (var i = 0; i < lines.length; i++) {
            var match = CANDIDATE_LINE.exec(lines[i]);
            if (match && MDNS_NAME.test(match[2])) {
                lines[i] = match[1] + localIp + match[3];
                hits++;
            }
        }
        if (!hits) return null;
        return { sdp: lines.join('\r\n'), hits: hits };
    }

    var CANDIDATE_PARTS = /^a=candidate:\S+ \d+ \S+ \d+ (\S+) (\d+) typ (\S+)/i;

    function remoteCandidates(description) {
        var found = [];
        var hadMdns = false;
        var sdp = description && description.sdp;
        if (!sdp) return { found: found, hadMdns: hadMdns };
        var lines = sdp.split(/\r\n|\r|\n/);
        for (var i = 0; i < lines.length; i++) {
            var match = CANDIDATE_PARTS.exec(lines[i]);
            if (!match) continue;
            if (MDNS_NAME.test(match[1])) hadMdns = true;
            found.push({ address: match[1], port: Number(match[2]), type: match[3] });
        }
        return { found: found, hadMdns: hadMdns };
    }

    function instrument(pc, config) {
        var iceServers = (config && config.iceServers) || [];
        var lanMode = iceServers.length === 0;
        var startedAt = Date.now();
        var remoteSetAt = 0;
        var remote = { found: [], hadMdns: false };
        var rewritten = 0;
        var reported = false;
        var sdpLogged = false;

        log('pc: iceServers=' + JSON.stringify(iceServers) +
            (config && config.iceTransportPolicy ? ' policy=' + config.iceTransportPolicy : ''));

        if (lanMode && localDescriptionGetter) {
            try {
                Object.defineProperty(pc, 'localDescription', {
                    configurable: true,
                    get: function () {
                        var live = localDescriptionGetter.call(pc);
                        if (!live) return live;
                        if (!sdpLogged) {
                            sdpLogged = true;
                            log('local sdp:\n' + live.sdp);
                        }
                        var rewrittenSdp = rewriteSdp(live.sdp);
                        if (!rewrittenSdp) return live;
                        rewritten = rewrittenSdp.hits;
                        log('rewrote ' + rewrittenSdp.hits + ' mDNS candidate(s) to ' + localIp);
                        return {
                            type: live.type,
                            sdp: rewrittenSdp.sdp,
                            toJSON: function () { return { type: live.type, sdp: rewrittenSdp.sdp }; }
                        };
                    }
                });
            } catch (e) {
                log('cannot patch localDescription: ' + e);
            }
        }

        pc.addEventListener('icecandidate', function (event) {
            var candidate = event.candidate;
            if (!candidate) {
                log('ice: gathering done');
                return;
            }
            var hidden = candidate.address && MDNS_NAME.test(candidate.address);
            log('ice: ' + candidate.type + ' ' + candidate.protocol + ' ' +
                candidate.address + ':' + candidate.port + (hidden ? ' (mDNS)' : ''));
        });

        pc.addEventListener('icegatheringstatechange', function () {
            log('ice: gathering=' + pc.iceGatheringState);
        });

        pc.addEventListener('iceconnectionstatechange', function () {
            log('ice: connection=' + pc.iceConnectionState);
        });

        pc.addEventListener('signalingstatechange', function () {
            log('sdp: signaling=' + pc.signalingState);
        });

        pc.addEventListener('connectionstatechange', function () {
            log('pc: state=' + pc.connectionState);
        });

        var setRemote = pc.setRemoteDescription.bind(pc);
        pc.setRemoteDescription = function (description) {
            try {
                var scan = remoteCandidates(description);
                if (scan.found.length) {
                    remote = scan;
                    log('remote candidates=' + JSON.stringify(scan.found) +
                        (scan.hadMdns ? ' (contains mDNS names)' : ''));
                }
            } catch (e) { }
            return setRemote(description).then(function () {
                remoteSetAt = Date.now();
            }, function (error) {
                log('setRemoteDescription failed: ' + (error && error.message ? error.message : error));
                throw error;
            });
        };

        var addIce = pc.addIceCandidate.bind(pc);
        pc.addIceCandidate = function (candidate) {
            if (candidate && candidate.address && MDNS_NAME.test(candidate.address)) {
                remote.hadMdns = true;
                remote.found.push({ address: candidate.address, port: candidate.port || 0, type: candidate.type || 'host' });
            }
            return addIce(candidate).catch(function (error) {
                log('addIceCandidate failed: ' + (error && error.message ? error.message : error));
                throw error;
            });
        };

        if (!lanMode) return;

        var timer = window.setInterval(function () {
            if (reported) {
                window.clearInterval(timer);
                return;
            }
            var state = pc.connectionState;
            if (state === 'connected' || state === 'closed') {
                window.clearInterval(timer);
                return;
            }
            if (!remoteSetAt || Date.now() - remoteSetAt < 14000) return;
            reported = true;
            window.clearInterval(timer);
            var summary = {
                ageSeconds: Math.round((Date.now() - startedAt) / 1000),
                localIp: localIp,
                localIps: addresses,
                mdnsRewritten: rewritten,
                remoteHadMdns: remote.hadMdns,
                remoteCandidates: remote.found.slice(0, 8),
                signalingState: pc.signalingState,
                iceConnectionState: pc.iceConnectionState,
                connectionState: pc.connectionState
            };
            log('pairing stalled: ' + JSON.stringify(summary));
            try {
                if (bridge && bridge.stalled) bridge.stalled(JSON.stringify(summary));
            } catch (e) {
                log('stalled() failed: ' + e);
            }
        }, 2000);
    }

    function PatchedRTCPeerConnection(config, constraints) {
        var pc = arguments.length > 1 ? new Native(config, constraints) : new Native(config);
        try {
            instrument(pc, config);
        } catch (e) {
            log('instrument failed: ' + e);
        }
        return pc;
    }

    PatchedRTCPeerConnection.prototype = Native.prototype;
    Object.getOwnPropertyNames(Native).forEach(function (name) {
        if (name === 'prototype' || name === 'length' || name === 'name' ||
            name === 'arguments' || name === 'caller') return;
        try {
            Object.defineProperty(PatchedRTCPeerConnection, name, Object.getOwnPropertyDescriptor(Native, name));
        } catch (e) { }
    });
    window.RTCPeerConnection = PatchedRTCPeerConnection;
    log('shim installed');
})();
