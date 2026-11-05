package com.iris.localai.v2217

import com.iris.localai.ui.markdown.HtmlBlocks
import com.iris.localai.ui.markdown.HtmlSandbox
import com.iris.localai.ui.markdown.ThreeAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.2.17 A: bundled Three.js — CDN URL mapping, document rewrite, import map, sandbox rules. */
class V2217Test {
    private val mod = "three/build/three.module.js"
    private val cls = "three/build/three.min.js"

    @Test fun moduleUrlsAnyVersionMapToBundledModule() {
        listOf(
            "https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.js",
            "https://cdn.jsdelivr.net/npm/three@0.181.2/build/three.module.min.js",
            "https://cdn.jsdelivr.net/npm/three/build/three.module.js",
            "https://cdn.jsdelivr.net/npm/three@0.170/+esm",
            "https://fastly.jsdelivr.net/npm/three@0.152.2/build/three.module.js",
            "https://unpkg.com/three@0.150.1/build/three.module.js?module",
            "https://unpkg.com/three",
            "https://esm.sh/three@0.160.0",
            "https://esm.sh/three",
            "https://esm.sh/v135/three@0.160.0/es2022/three.mjs",
            "https://cdn.skypack.dev/three@0.132.2",
            "https://ga.jspm.io/npm:three@0.160.0/build/three.module.js",
            "https://cdnjs.cloudflare.com/ajax/libs/three.js/0.160.0/three.module.min.js",
            "https://cdn.jsdelivr.net/npm/three@0.172.0/build/three.core.js",
            "https://iridis.local/three/build/three.module.js",
        ).forEach { assertEquals(it, mod, ThreeAssets.assetFor(it)) }
    }

    @Test fun classicUrlsMapToGlobalBuild() {
        listOf(
            "https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js",
            "https://cdnjs.cloudflare.com/ajax/libs/three.js/r134/three.js",
            "https://cdn.jsdelivr.net/npm/three@0.128.0/build/three.min.js",
            "https://unpkg.com/three@0.149.0/build/three.js",
            "https://unpkg.com/three@0.160.0/build/three.cjs",
        ).forEach { assertEquals(it, cls, ThreeAssets.assetFor(it)) }
    }

    @Test fun addonsMapToBundledFiles() {
        val orbit = "three/examples/jsm/controls/OrbitControls.js"
        assertEquals(orbit, ThreeAssets.assetFor("https://cdn.jsdelivr.net/npm/three@0.160.0/examples/jsm/controls/OrbitControls.js"))
        assertEquals(orbit, ThreeAssets.assetFor("https://unpkg.com/three@0.165.0/addons/controls/OrbitControls.js"))
        assertEquals(orbit, ThreeAssets.assetFor("https://esm.sh/three@0.160.0/examples/jsm/controls/OrbitControls.js"))
        assertEquals(orbit, ThreeAssets.assetFor("https://cdn.jsdelivr.net/npm/three@0.160/examples/jsm/controls/OrbitControls.js/+esm"))
        assertEquals(orbit, ThreeAssets.assetFor("https://esm.sh/three/addons/controls/OrbitControls"))
        assertEquals("three/examples/jsm/loaders/GLTFLoader.js", ThreeAssets.assetFor("https://unpkg.com/three@0.150.1/examples/jsm/loaders/GLTFLoader.js"))
        assertEquals("three/examples/jsm/utils/BufferGeometryUtils.js", ThreeAssets.assetFor("https://iridis.local/three/examples/jsm/utils/BufferGeometryUtils.js"))
        assertEquals("three/examples/js/controls/OrbitControls.js", ThreeAssets.assetFor("https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/controls/OrbitControls.js"))
        // relative import inside GLTFLoader resolves against the local root
        assertEquals("three/examples/jsm/utils/BufferGeometryUtils.js",
            java.net.URI("https://iridis.local/three/examples/jsm/loaders/GLTFLoader.js").resolve("../utils/BufferGeometryUtils.js").toString().let { ThreeAssets.assetFor(it) })
    }

    @Test fun everythingElseIsNotServed() {
        listOf(
            "https://cdn.jsdelivr.net/npm/three@0.160.0/examples/jsm/postprocessing/EffectComposer.js", // not bundled
            "https://cdn.jsdelivr.net/npm/three@0.170.0/build/three.webgpu.js",
            "https://cdn.jsdelivr.net/npm/three-stdlib@2/index.js",
            "https://cdn.jsdelivr.net/npm/threejs-extra/build/three.module.js",
            "https://unpkg.com/react@18/umd/react.production.min.js",
            "https://cdn.jsdelivr.net/npm/chart.js",
            "https://evil.example.com/three@0.160.0/build/three.module.js",
            "https://evil.example.com/npm/three/build/three.module.js",
            "https://iridis.local/secret.js",
            "file:///android_asset/three/build/three.module.js",
            "data:text/javascript,alert(1)",
        ).forEach { assertNull(it, ThreeAssets.assetFor(it)) }
        assertFalse(HtmlSandbox.allowRequest("https://unpkg.com/react@18/umd/react.production.min.js", allowImages = true, isImage = false))
    }

    @Test fun bundledAssetFilesExist() {
        val root = listOf(File("app/src/main/assets"), File("src/main/assets")).first { it.isDirectory }
        (ThreeAssets.BUNDLED - ThreeAssets.GENERATED).forEach { assertTrue(it, File(root, "three/$it").length() > 1000) }
        assertTrue(File(root, "three/build/three.module.js").readText().contains("REVISION=\"170\"") ||
            File(root, "three/build/three.module.js").readText().contains("\"170\""))
        assertTrue(File(root, "licenses/mit-three.js.txt").readText().contains("MIT License"))
        assertTrue(File(root, "licenses/index.json").readText().contains("mit-three.js.txt"))
    }

    @Test fun documentRewritesCdnAndAddsImportMap() {
        val src = """<html><head><script type="importmap">{"imports":{"three":"https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.js"}}</script></head>
            |<body><script type="module">import * as THREE from 'three';import {OrbitControls} from 'three/addons/controls/OrbitControls.js';</script></body></html>""".trimMargin()
        val doc = HtmlSandbox.document(src, HtmlBlocks.Kind.HTML, allowImages = false)
        assertFalse(doc.contains("cdn.jsdelivr.net/npm"))
        assertTrue(doc.contains("\"three\":\"https://iridis.local/three/build/three.module.js\""))
        assertTrue(doc.contains("\"three/addons/\":\"https://iridis.local/three/examples/jsm/\""))
        assertEquals(1, Regex("importmap").findAll(doc).count())
        assertTrue(doc.indexOf("Content-Security-Policy") < doc.indexOf("importmap"))

        // no import map at all: one is injected first in <head>
        val bare = HtmlSandbox.document("<script type=\"module\">import * as THREE from \"three\";</script>", HtmlBlocks.Kind.HTML, false)
        assertTrue(bare.indexOf("<script type=\"importmap\">") in bare.indexOf("<head>")..bare.indexOf("<body>"))
        // importmap with only addons prefix pointing to unpkg
        val (p, inj) = ThreeAssets.prepare("""<script type="importmap">{"imports":{"three/addons/":"https://unpkg.com/three@0.158.0/examples/jsm/"}}</script>""")
        assertNull(inj)
        assertTrue(p.contains("\"three/addons/\":\"https://iridis.local/three/examples/jsm/\""))
        assertTrue(p.contains("\"three\":\"https://iridis.local/three/build/three.module.js\""))
        // classic script tag
        val classic = HtmlSandbox.document("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js\"></script>", HtmlBlocks.Kind.HTML, false)
        assertTrue(classic.contains("src=\"https://iridis.local/three/build/three.min.js\""))
        // unrelated pages get no import map; other script URLs are left as they are (and stay blocked)
        val plain = HtmlSandbox.document("<p>hi</p><script src=\"https://unpkg.com/react@18/x.js\"></script>", HtmlBlocks.Kind.HTML, false)
        assertFalse(plain.contains("importmap")); assertTrue(plain.contains("https://unpkg.com/react@18/x.js"))
    }

    @Test fun cspAndLinks() {
        val csp = HtmlSandbox.csp(false)
        assertTrue(csp.contains("script-src 'unsafe-inline'"))
        assertTrue(csp.contains("'self' iridis.local cdn.jsdelivr.net"))
        assertTrue(csp.contains("connect-src 'none'"))
        assertFalse(csp.contains("https:"))
        assertEquals("https://iridis.local/", ThreeAssets.BASE)
        assertFalse(HtmlSandbox.externalLink("https://iridis.local/page.html"))
        assertTrue(HtmlSandbox.externalLink("https://example.com/"))
        assertEquals("*", ThreeAssets.HEADERS["Access-Control-Allow-Origin"])
        assertEquals("text/javascript", ThreeAssets.MIME)
    }
}
