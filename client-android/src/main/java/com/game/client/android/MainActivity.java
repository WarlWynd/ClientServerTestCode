package com.game.client.android;

import com.game.client.ClientMain;
import com.jme3.app.AndroidHarness;

public class MainActivity extends AndroidHarness {

    public MainActivity() {
        appClass = ClientMain.class.getName();
        eglBitsPerPixel = 24;
        eglAlphaBits    = 0;
        eglDepthBits    = 16;
        eglSamples      = 0;
        eglStencilBits  = 0;
        frameRate       = -1;
        exitDialogTitle   = "Quit Adventure Friends?";
        exitDialogMessage = "Are you sure you want to quit?";
    }
}
