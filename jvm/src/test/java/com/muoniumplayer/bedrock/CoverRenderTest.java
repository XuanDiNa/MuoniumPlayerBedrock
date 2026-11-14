package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaHost;
import com.muoniumplayer.render.skija.SkijaImages;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

/** A worker-decoded cover must reach the original upload queue and actual GPU. */
class CoverRenderTest {
    @Test void asynchronousCoverIsVisibleInRoundedOriginalImagePath() throws Exception {
        System.setProperty("muonium.bedrock.offlineTest", "true");
        assertTrue(GLFW.glfwInit());
        long window = 0;
        try {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 8);
            window = GLFW.glfwCreateWindow(640, 480, "Cover fixture", 0, 0);
            assertNotEquals(0, window);
            GLFW.glfwMakeContextCurrent(window); GL.createCapabilities();
            var client = BedrockClient.getInstance(); client.bindWindow(window);
            client.getWindow().size(640, 480, 1);
            MuoniumPlayerClient.getInstance().init();
            // Upload after Skia has rendered, matching a live asynchronous cover.
            for (int i = 0; i < 4; i++) {
                SkijaHost.beginFrame();
                com.muoniumplayer.core.screens.ncm.NCMScreen.getInstance().resize(640,480);
                com.muoniumplayer.core.screens.ncm.NCMScreen.getInstance().drawScreen(0,0,0);
                SkijaHost.endFrame();
                GLFW.glfwSwapBuffers(window);
            }
            var location = Location.of("cover-fixture");
            var cover = new BufferedImage(320, 320, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 320; y++) for (int x = 0; x < 320; x++) cover.setRGB(x, y, 0xFF269858);
            Thread worker = new Thread(() -> Textures.loadTexture(location, cover, true, true));
            worker.start(); worker.join();
            assertTrue(Textures.pendingUploads() > 0);
            MuoniumPlayerClient.getInstance().tick();
            var texture = TextureManager.getInstance().getTexture(location);
            assertNotNull(texture, "Decoded cover must upload through the original queue");
            assertEquals(0, org.lwjgl.opengl.GL11.glGetError(), "Cover upload GL error");
            SkijaHost.beginFrame(0xFF15151A);
            try {
                SkijaGfx.setBoundTexture(texture.getGlTextureId());
                assertTrue(SkijaImages.drawBoundRoundedTextured(100, 100, 220, 220, 12, 0, 0, 1, 1, 1));
            } finally { SkijaHost.endFrame(); }
            Path capture = Path.of("../stage-captures/cover.png");
            java.nio.file.Files.createDirectories(capture.getParent());
            SkijaHost.savePng(capture);
            var image = ImageIO.read(capture.toFile());
            int rgb = image.getRGB(210, 210);
            assertTrue((rgb >> 8 & 255) > 100, "The uploaded cover must be visible, not a dark placeholder");
            // Asymmetric pixels detect incorrect texture-size declarations that
            // a solid-color fixture cannot expose.
            for (int y = 0; y < 320; y++) for (int x = 0; x < 320; x++)
                cover.setRGB(x,y, x < 160 ? 0xFFFF2020 : 0xFF2020FF);

            // Exercise the real song-cover service, including its default upload
            // options, then the UI's explicit image-size path (not just bound RQT).
            byte[] png;
            try (var out = new java.io.ByteArrayOutputStream()) {
                ImageIO.write(cover, "PNG", out); png = out.toByteArray();
            }
            var server = new java.net.ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress());
            Thread http = new Thread(() -> {
                while (!server.isClosed()) try (var socket = server.accept()) {
                    var in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                    while (true) { var line = in.readLine(); if (line == null || line.isEmpty()) break; }
                    var out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + png.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    out.write(png); out.flush();
                } catch (java.io.IOException e) { if (!server.isClosed()) throw new RuntimeException(e); }
            });
            http.setDaemon(true); http.start();
            try {
                String json = "{\"id\":123,\"name\":\"Cover fixture\",\"al\":{\"picUrl\":\"http://127.0.0.1:"
                        + server.getLocalPort() + "/cover\"}}";
                var song = new com.google.gson.Gson().fromJson(json, com.muoniumplayer.core.ncm.music.dto.Music.class);
                com.muoniumplayer.core.ncm.music.CloudMusic.loadMusicCover(song);
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (TextureManager.getInstance().getTexture(song.getCoverLocation()) == null && System.nanoTime() < deadline) {
                    MuoniumPlayerClient.getInstance().tick(); Thread.sleep(10);
                }
                assertNotNull(TextureManager.getInstance().getTexture(song.getCoverLocation()), "Real cover service must finish");
                SkijaHost.beginFrame(0xFF15151A);
                com.muoniumplayer.core.rendering.Image.draw(song.getCoverLocation(), 100, 100, 160, 160);
                SkijaHost.endFrame();
                SkijaHost.savePng(Path.of("../stage-captures/song-cover.png"));
                int actual = ImageIO.read(Path.of("../stage-captures/song-cover.png").toFile()).getRGB(180, 180);
                int right = ImageIO.read(Path.of("../stage-captures/song-cover.png").toFile()).getRGB(235, 180);
                assertTrue((right & 255) > 180 && (right >> 16 & 255) < 80,
                        "The right half must sample the full cover at its actual source size: " + Integer.toHexString(right));
                var widget = new com.muoniumplayer.core.rendering.ui.widgets.RoundedImageWidget(
                        song.getCoverLocation(), 100,100,160,160).fadeIn().setRadius(10);
                for (int frame = 0; frame < 40; frame++) {
                    SkijaHost.beginFrame(0xFF15151A);
                    widget.onRender(0,0);
                    SkijaHost.endFrame();
                    GLFW.glfwSwapBuffers(window); Thread.sleep(16);
                }
                assertTrue(widget.getWidgetAlpha() > .9f, "Original cover widget fade must progress in owned host frames");
                Path faded = Path.of("../stage-captures/faded-cover.png");
                SkijaHost.savePng(faded);
                var fadedImage = ImageIO.read(faded.toFile());
                int leftPixel = fadedImage.getRGB(125, 180), rightPixel = fadedImage.getRGB(235, 180);
                assertTrue((leftPixel >> 16 & 255) > 180 && (leftPixel & 255) < 80,
                        "Original fading widget must actually paint the red cover half");
                assertTrue((rightPixel & 255) > 180 && (rightPixel >> 16 & 255) < 80,
                        "Original fading widget must actually paint the blue cover half");
            } finally { server.close(); http.join(1000); }
            TextureManager.getInstance().deleteTexture(location);
        } finally {
            SkijaImages.clear(); SkijaHost.shutdown();
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }
}
