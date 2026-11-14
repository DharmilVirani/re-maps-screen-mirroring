package com.dharmil.mirrordash.panel;

interface IDisplayPowerService {
    boolean setDisplayPower(boolean on) = 1;
    void destroy() = 16777114;
}
