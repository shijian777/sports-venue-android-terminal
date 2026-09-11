package com.codex.lockertest.face.verification;

public final class FaceJpegContract {
    public static final int MAX_BYTES = 1_048_576;

    public interface Decoder {
        Dimensions decode(byte[] jpeg);
    }

    public static final class Dimensions {
        private final int width;
        private final int height;

        public Dimensions(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }
    }

    private FaceJpegContract() {
    }

    public static boolean isValid(byte[] jpeg, Decoder decoder) {
        if (jpeg == null || decoder == null || jpeg.length < 4 || jpeg.length > MAX_BYTES) {
            return false;
        }
        int last = jpeg.length - 1;
        if ((jpeg[0] & 0xff) != 0xff || (jpeg[1] & 0xff) != 0xd8
                || (jpeg[last - 1] & 0xff) != 0xff || (jpeg[last] & 0xff) != 0xd9) {
            return false;
        }
        try {
            Dimensions dimensions = decoder.decode(jpeg);
            return dimensions != null && dimensions.width() > 0 && dimensions.height() > 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
