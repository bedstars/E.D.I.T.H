package dev.edith.ai;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** Turns screenshots / dropped files into API-ready JPEGs (max 1568 px on the long side). */
public final class ImageUtil {
    private ImageUtil() {}
    public static final int MAX_SIDE = 1568;

    public static AiClient.Img fromBytes(byte[] raw) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
        if (src == null) throw new IOException("unsupported format (use PNG, JPG or GIF)");
        int w = src.getWidth(), h = src.getHeight();
        double sc = Math.min(1.0, (double) MAX_SIDE / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * sc)), nh = Math.max(1, (int) Math.round(h * sc));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, nw, nh);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageWriter wr = ImageIO.getImageWritersByFormatName("jpg").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            ImageWriteParam p = wr.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.9f);
            wr.setOutput(ios);
            wr.write(null, new IIOImage(out, null, null), p);
        } finally { wr.dispose(); }
        return new AiClient.Img("image/jpeg", Base64.getEncoder().encodeToString(bos.toByteArray()), nw, nh);
    }

    /** Grabs the current game framebuffer. Must run on the render/client thread. */
    public static AiClient.Img captureScreen(MinecraftClient mc) throws IOException {
        Path tmp = Files.createTempFile("edith", ".png");
        try {
            try (NativeImage img = ScreenshotRecorder.takeScreenshot(mc.getFramebuffer())) { img.writeTo(tmp); }
            return fromBytes(Files.readAllBytes(tmp));
        } finally { Files.deleteIfExists(tmp); }
    }
}
