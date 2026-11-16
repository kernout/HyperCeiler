/*
 * This file is part of HyperCeiler.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.camera;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.util.Size;
import android.view.Surface;

import java.util.function.Consumer;

/** An EGL pbuffer only: no window, Activity, View, display wake-up or visible preview. */
final class ScreenOffPreview implements AutoCloseable {
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture texture;
    private Surface surface;
    private int textureId;
    private boolean closed;

    ScreenOffPreview(Size size, Handler handler, Consumer<RuntimeException> onError) {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            require(display != EGL14.EGL_NO_DISPLAY
                    && EGL14.eglInitialize(display, version, 0, version, 1), "eglInitialize");
            int[] attributes = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            require(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
                    && count[0] > 0, "eglChooseConfig");
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            require(context != EGL14.EGL_NO_CONTEXT, "eglCreateContext");
            pbuffer = EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            require(pbuffer != EGL14.EGL_NO_SURFACE, "eglCreatePbufferSurface");
            makeCurrent();
            int[] names = new int[1];
            GLES20.glGenTextures(1, names, 0);
            textureId = names[0];
            require(textureId != 0, "glGenTextures");
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            texture = new SurfaceTexture(textureId);
            texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
            texture.setOnFrameAvailableListener(frame -> {
                if (closed) return;
                try {
                    makeCurrent();
                    // Drain every queued preview frame, avoiding a producer stall during 3A.
                    // No pixel readback, analysis, screenshot or image logging is performed.
                    frame.updateTexImage();
                } catch (RuntimeException e) {
                    onError.accept(e);
                }
            }, handler);
            surface = new Surface(texture);
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    Surface surface() {
        return surface;
    }

    private void makeCurrent() {
        require(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context), "eglMakeCurrent");
    }

    private static void require(boolean success, String operation) {
        if (!success) throw new IllegalStateException(operation + " failed: EGL error="
                + Integer.toHexString(EGL14.eglGetError()));
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (surface != null) surface.release();
        if (texture != null) {
            texture.setOnFrameAvailableListener(null);
            texture.release();
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE
                    && EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context) && textureId != 0)
                GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
        }
    }
}
