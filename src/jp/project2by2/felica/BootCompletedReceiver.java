//
// Copyright (C) 2026 The 2by2 Project
// SPDX-License-Identifier: Apache-2.0
//

package jp.project2by2.felica;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootCompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        FelicaService.startRegistrationThread();
    }
}
