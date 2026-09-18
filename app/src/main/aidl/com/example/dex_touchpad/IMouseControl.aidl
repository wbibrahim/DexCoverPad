package com.example.dex_touchpad;

interface IMouseControl {
    void moveCursor(float deltaX, float deltaY) = 1;
    void sendClick(int buttonCode) = 2;
    void sendScroll(float verticalDelta, float horizontalDelta) = 3;
    boolean isReady() = 4;
    String getError() = 5;
    void destroy() = 16777114;
}
