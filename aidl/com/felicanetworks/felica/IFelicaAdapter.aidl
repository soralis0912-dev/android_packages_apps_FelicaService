package com.felicanetworks.felica;

import com.felicanetworks.felica.IFelicaAdapterExtra;
import com.felicanetworks.felica.IFelicaRf;
import com.felicanetworks.felica.IFelicaSe;

interface IFelicaAdapter {
    IFelicaSe getFelicaSeInterface(String packageName);
    IFelicaRf getFelicaRfInterface(String packageName);
    IFelicaAdapterExtra getFelicaAdapterExtraInterface(String packageName);
}
