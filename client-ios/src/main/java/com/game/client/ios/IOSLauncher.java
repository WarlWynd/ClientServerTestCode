package com.game.client.ios;

import com.game.client.ClientMain;
import com.jme3.system.ios.JmeAppDelegateInstaller;

public class IOSLauncher {

    public static void main(String[] argv) {
        JmeAppDelegateInstaller.installAppDelegate(ClientMain.class);
    }
}
