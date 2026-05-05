package com.felicanetworks.felica;

interface IFelicaAdapterExtra {
    boolean enable();
    boolean disable(boolean persist);
    int getState();
    int getRwP2pState();
    boolean setRwP2pMode(boolean isEnable);
    void prepareSwitchedOffState();
}
