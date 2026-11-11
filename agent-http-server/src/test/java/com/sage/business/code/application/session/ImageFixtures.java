package com.sage.business.code.application.session;

import java.io.ByteArrayOutputStream;
import java.util.zip.CRC32;

/** WU-10 测试共用的头部合法 PNG/JPEG/APNG 构造器（不做像素解码）。 */
public final class ImageFixtures {
    private ImageFixtures() {
    }

    private static void be32(ByteArrayOutputStream out, long value) {
        out.write((int) ((value >>> 24) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) (value & 0xFF));
    }

    private static void be16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        be32(out, data.length);
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(typeBytes[0]); out.write(typeBytes[1]);
        out.write(typeBytes[2]); out.write(typeBytes[3]);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(typeBytes, 0, 4);
        crc.update(data, 0, data.length);
        be32(out, crc.getValue());
    }

    private static byte[] ihdr(int width, int height) {
        // IHDR data: width(4) height(4) bitDepth(1) colorType(1) compression(1) filter(1) interlace(1)
        byte[] ihdr = new byte[13];
        ByteArrayOutputStream w = new ByteArrayOutputStream();
        be32(w, width);
        ByteArrayOutputStream h = new ByteArrayOutputStream();
        be32(h, height);
        System.arraycopy(w.toByteArray(), 0, ihdr, 0, 4);
        System.arraycopy(h.toByteArray(), 0, ihdr, 4, 4);
        ihdr[8] = 8;   // bit depth
        ihdr[9] = 6;   // color type RGBA
        ihdr[10] = 0;  // compression
        ihdr[11] = 0;  // filter
        ihdr[12] = 0;  // interlace
        return ihdr;
    }

    /** Minimal header-valid PNG (IHDR + IDAT + IEND); pixel data is not decoded. */
    public static byte[] png(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x89); out.write('P'); out.write('N'); out.write('G');
        out.write(0x0D); out.write(0x0A); out.write(0x1A); out.write(0x0A);
        chunk(out, "IHDR", ihdr(width, height));
        chunk(out, "IDAT", new byte[] {0x78, 0x01, 0x00, 0x00, 0x00, (byte) 0xFF, (byte) 0xFF});
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** PNG with a large IDAT payload (header-valid, still never decoded). */
    public static byte[] pngWithIdatBytes(int width, int height, int idatBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x89); out.write('P'); out.write('N'); out.write('G');
        out.write(0x0D); out.write(0x0A); out.write(0x1A); out.write(0x0A);
        chunk(out, "IHDR", ihdr(width, height));
        chunk(out, "IDAT", new byte[idatBytes]);
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** PNG with an acTL chunk before the first IDAT (APNG marker). */
    public static byte[] apng(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x89); out.write('P'); out.write('N'); out.write('G');
        out.write(0x0D); out.write(0x0A); out.write(0x1A); out.write(0x0A);
        chunk(out, "IHDR", ihdr(width, height));
        ByteArrayOutputStream actl = new ByteArrayOutputStream();
        be32(actl, 2); be32(actl, 0);
        chunk(out, "acTL", actl.toByteArray());
        chunk(out, "IDAT", new byte[] {0x78, 0x01, 0x00, 0x00, 0x00, (byte) 0xFF, (byte) 0xFF});
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** Header-valid JPEG (SOI + APP0 + SOF0 + SOS); scan data is not decoded. */
    public static byte[] jpeg(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF); out.write(0xD8);              // SOI
        out.write(0xFF); out.write(0xE0);              // APP0
        be16(out, 16);
        out.write('J'); out.write('F'); out.write('I'); out.write('F');
        out.write(0); out.write(1); out.write(1); out.write(0);
        be16(out, 1); be16(out, 1); out.write(0); out.write(0);
        out.write(0xFF); out.write(0xC0);              // SOF0
        be16(out, 11);
        out.write(8);                                  // precision
        be16(out, height);
        be16(out, width);
        out.write(3);                                  // components
        out.write(1); out.write(0x22); out.write(0);
        out.write(2); out.write(0x11); out.write(1);
        out.write(3); out.write(0x11); out.write(1);
        out.write(0xFF); out.write(0xDA);              // SOS
        be16(out, 8);
        out.write(1); out.write(0x00); out.write(0x2B);
        return out.toByteArray();
    }
}
