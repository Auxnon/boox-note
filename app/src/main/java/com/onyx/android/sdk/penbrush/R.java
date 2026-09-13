package com.onyx.android.sdk.penbrush;

/**
 * Resource-id shim for the pencil brush mask.
 *
 * NeoPencilPen builds its brush mask from a 256x256 graphite texture it looks up as
 * com.onyx.android.sdk.penbrush.R.drawable.pencil. That resource ships in the onyxsdk-penbrush
 * artifact, which we deliberately do not depend on: it also republishes the whole Neo pen engine,
 * which collides with the firmware-extracted onyxsdk-pen-native-classes.jar this project already
 * relies on - and it references NeoPenNative without shipping it, so it cannot simply replace that
 * jar either.
 *
 * The only thing actually missing was the drawable, so it is vendored into our own res/ and this
 * class maps the id the engine asks for onto it. Without it the engine threw NoClassDefFoundError
 * on R$drawable, its caller caught that, and every pencil stroke fell back to a textureless stroke.
 */
public final class R {

    private R() {
    }

    public static final class drawable {

        public static final int pencil = com.auxnon.booxnote.R.drawable.onyx_pencil_brush;

        private drawable() {
        }
    }
}
